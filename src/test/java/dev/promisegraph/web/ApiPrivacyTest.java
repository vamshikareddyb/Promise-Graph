package dev.promisegraph.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.promisegraph.domain.Evidence;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.store.AuditLog;
import dev.promisegraph.store.Repository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The API enforces the visibility rules server-side. Workers stay off; no Temporal server is needed. */
@SpringBootTest(properties = "promisegraph.temporal.worker-enabled=false")
@AutoConfigureMockMvc
class ApiPrivacyTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    Repository repo;

    @Autowired
    AuditLog audit;

    @BeforeEach
    void seed() {
        repo.createPromise(new Promise("p-neha", "demo", "neha", "ship the data export API", PromiseStatus.ACTIVE, 0.9,
                "2026-11-02T05:00:00Z", "2026-11-02T05:00:00Z", null, "2026-11-13", "platform",
                List.of(new Evidence("m1", "I'll ship the export API by 13 Nov.", "platform")), 1));
        repo.createPromise(new Promise("p-arjun", "demo", "arjun", "migrate the billing tables", PromiseStatus.ACTIVE, 0.9,
                "2026-11-02T05:00:00Z", "2026-11-02T05:00:00Z", null, "2026-11-14", "platform", List.of(), 1));
        repo.createPromise(new Promise("p-arjun2", "demo", "arjun", "archive the old billing tables", PromiseStatus.ACTIVE, 0.9,
                "2026-11-02T05:00:00Z", "2026-11-02T05:00:00Z", null, "2026-11-20", "platform", List.of(), 1));
    }

    @Test
    void everyDataRequestMustSayWhoIsViewing() throws Exception {
        mvc.perform(get("/api/promises")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/people")).andExpect(status().isOk());
    }

    @Test
    void peopleSeeTheirOwnPromisesAndLeadsSeeTheTeamWithoutQuotes() throws Exception {
        mvc.perform(get("/api/promises").header(ApiController.VIEWER, "neha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'p-neha')].latestQuote").value("I'll ship the export API by 13 Nov."))
                .andExpect(jsonPath("$[?(@.id == 'p-arjun')]").isEmpty());
        mvc.perform(get("/api/promises").header(ApiController.VIEWER, "ravi"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].latestQuote").doesNotExist());
        mvc.perform(get("/api/promises").header(ApiController.VIEWER, "dev")).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void inboxesAndOperationsAreGuarded() throws Exception {
        mvc.perform(get("/api/inbox").param("person", "neha").header(ApiController.VIEWER, "arjun")).andExpect(status().isForbidden());
        mvc.perform(get("/api/inbox").param("person", "neha").header(ApiController.VIEWER, "neha")).andExpect(status().isOk());
        mvc.perform(get("/api/ops/dead-letters").header(ApiController.VIEWER, "neha")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ops/dead-letters").header(ApiController.VIEWER, "ravi")).andExpect(status().isOk());
        mvc.perform(post("/api/absences").header(ApiController.VIEWER, "arjun").contentType(MediaType.APPLICATION_JSON)
                .content("{\"person\":\"neha\",\"startsOn\":\"2030-01-10\",\"endsOn\":\"2030-01-12\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void changesAreAudited() throws Exception {
        int before = audit.recent("demo", 1000).size();
        mvc.perform(post("/api/links").header(ApiController.VIEWER, "neha").contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"p-arjun\",\"to\":\"p-arjun2\"}"))
                .andExpect(status().isNotFound()); // neha can't see arjun's promises, so she can't link them
        mvc.perform(post("/api/links").header(ApiController.VIEWER, "ravi").contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"p-arjun\",\"to\":\"p-arjun2\"}"))
                .andExpect(status().isCreated());
        org.assertj.core.api.Assertions.assertThat(audit.recent("demo", 1000)).hasSize(before + 1)
                .first().satisfies(e -> {
                    org.assertj.core.api.Assertions.assertThat(e.actor()).isEqualTo("ravi");
                    org.assertj.core.api.Assertions.assertThat(e.action()).isEqualTo("link:add");
                });
    }
}
