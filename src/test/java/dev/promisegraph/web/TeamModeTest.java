package dev.promisegraph.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.inbox.Signals;
import dev.promisegraph.store.Repository;
import dev.promisegraph.team.Member;
import dev.promisegraph.team.TeamStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Team mode: everyone signs in, the server decides who is viewing, and each person sees only their own. */
@SpringBootTest(properties = {"promisegraph.temporal.worker-enabled=false", "promisegraph.auth.mode=google"})
@AutoConfigureMockMvc
class TeamModeTest {

    @DynamicPropertySource
    static void google(DynamicPropertyRegistry registry) throws IOException {
        Path creds = Files.createTempFile("pg-client", ".json");
        Files.writeString(creds, "{\"web\":{\"client_id\":\"id.apps.googleusercontent.com\",\"client_secret\":\"s\"}}");
        registry.add("promisegraph.gmail.credentials-file", creds::toString);
        registry.add("promisegraph.auth.secret-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    Repository repo;

    @Autowired
    TeamStore team;

    @MockitoBean
    Signals signals;

    @BeforeEach
    void seed() {
        team.addIfAbsent(new Member("demo", "boss@x.com", "Boss", "platform", "Lead", true, true, true, "2026-11-05T05:00:00Z"));
        team.addIfAbsent(new Member("demo", "neha@x.com", "Neha", "platform", "Engineer", false, false, true, "2026-11-05T05:00:00Z"));
        repo.createPromise(new Promise("t-neha", "demo", "neha@x.com", "ship the export API", PromiseStatus.ACTIVE, 0.9,
                "2026-11-05T05:00:00Z", "2026-11-05T05:00:00Z", null, "2026-11-13", "email:neha@x.com:t1", List.of(), 1));
        repo.createPromise(new Promise("t-boss", "demo", "boss@x.com", "review the budget", PromiseStatus.ACTIVE, 0.9,
                "2026-11-05T05:00:00Z", "2026-11-05T05:00:00Z", null, "2026-11-14", "email:boss@x.com:t2", List.of(), 1));
        repo.createPromise(new Promise("t-neha-done", "demo", "neha@x.com", "write the runbook", PromiseStatus.KEPT, 0.9,
                "2026-11-05T05:00:00Z", "2026-11-06T09:00:00Z", null, "2026-11-06", "email:neha@x.com:t3", List.of(), 2));
    }

    @Test
    void theOwnerMarksAPromiseDoneAndItEndsAsKept() throws Exception {
        mvc.perform(get("/api/promises").param("all", "true").sessionAttr(AuthController.USER, "neha@x.com"))
                .andExpect(jsonPath("$[?(@.id == 't-neha')].canComplete").value(true))
                .andExpect(jsonPath("$[?(@.id == 't-neha-done')].canComplete").value(false))
                .andExpect(jsonPath("$[?(@.id == 't-neha-done')].updatedAt").value("2026-11-06T09:00:00Z"));
        mvc.perform(post("/api/promises/t-neha/done").sessionAttr(AuthController.USER, "neha@x.com"))
                .andExpect(status().isOk());
        verify(signals).toPromise(eq("t-neha"), argThat(s -> s.kind().equals("kept") && s.actor().equals("neha@x.com")));

        mvc.perform(post("/api/promises/t-neha-done/done").sessionAttr(AuthController.USER, "neha@x.com"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/promises/t-boss/done").sessionAttr(AuthController.USER, "neha@x.com"))
                .andExpect(status().isNotFound()); // someone else's promise isn't even visible
        verify(signals, never()).toPromise(eq("t-boss"), argThat(s -> true));
    }

    @Test
    void signingInStartsAtGoogle() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("https://accounts.google.com/o/oauth2/v2/auth?client_id=id.apps")));
        mvc.perform(get("/login/callback").param("code", "x").param("state", "forged"))
                .andExpect(status().isBadRequest()); // no sign-in was started in this session
    }

    @Test
    void theViewerIsWhoeverIsSignedInNeverTheHeader() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.mode").value("google"));
        mvc.perform(get("/api/promises").header(ApiController.VIEWER, "boss@x.com")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/promises").sessionAttr(AuthController.USER, "neha@x.com").header(ApiController.VIEWER, "boss@x.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("t-neha"));
        mvc.perform(get("/api/me").sessionAttr(AuthController.USER, "neha@x.com"))
                .andExpect(jsonPath("$.email").value("neha@x.com"))
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void leadsDontSeeTheirTeamsPromises() throws Exception {
        mvc.perform(get("/api/promises").sessionAttr(AuthController.USER, "boss@x.com"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("t-boss"));
    }

    @Test
    void theCodecServerDecryptsOnlyForSignedInAdmins() throws Exception {
        String body = "{\"payloads\":[{\"metadata\":{},\"data\":\"" + Base64.getEncoder().encodeToString("x".getBytes()) + "\"}]}";
        mvc.perform(post("/codec/decode").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/codec/decode").sessionAttr(AuthController.USER, "neha@x.com").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/codec/decode").sessionAttr(AuthController.USER, "boss@x.com").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void onlyAdminsEditThePeoplePage() throws Exception {
        String body = "{\"team\":\"analytics\",\"role\":\"Data engineer\"}";
        mvc.perform(put("/api/team/neha@x.com").sessionAttr(AuthController.USER, "neha@x.com")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/team/neha@x.com").sessionAttr(AuthController.USER, "boss@x.com")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team").value("analytics"));
        mvc.perform(get("/api/people").sessionAttr(AuthController.USER, "neha@x.com"))
                .andExpect(jsonPath("$[?(@.id == 'neha@x.com')].team").value("analytics"));
    }
}
