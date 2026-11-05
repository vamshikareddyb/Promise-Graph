package dev.promisegraph.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.gmail.MailboxOwner;
import dev.promisegraph.store.Repository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** With a Gmail account connected, the app is that person's alone. */
@SpringBootTest(properties = "promisegraph.temporal.worker-enabled=false")
@AutoConfigureMockMvc
@Import(PersonalModeTest.Mailbox.class)
class PersonalModeTest {

    @TestConfiguration
    static class Mailbox {
        @Bean
        MailboxOwner mailboxOwner() {
            return MailboxOwner.fixed("Me@Gmail.com");
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    Repository repo;

    @BeforeEach
    void seed() {
        // One promise I made, one a friend made to me in an email I received.
        repo.createPromise(new Promise("g-mine", "demo", "me@gmail.com", "send the project report", PromiseStatus.ACTIVE, 0.9,
                "2026-11-04T05:00:00Z", "2026-11-04T05:00:00Z", null, "2026-11-06", "gmail", List.of(), 1));
        repo.createPromise(new Promise("g-friend", "demo", "friend@example.com", "share the slides", PromiseStatus.ACTIVE, 0.9,
                "2026-11-04T05:00:00Z", "2026-11-04T05:00:00Z", null, "2026-11-07", "gmail", List.of(), 1));
    }

    @Test
    void theOnlyViewerIsTheConnectedAccount() throws Exception {
        mvc.perform(get("/api/people"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("me@gmail.com"));
    }

    @Test
    void theAccountSeesEveryPromiseFromItsMailbox() throws Exception {
        mvc.perform(get("/api/promises").header(ApiController.VIEWER, "me@gmail.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'g-mine')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.id == 'g-friend')]").isNotEmpty());
    }

    @Test
    void nobodyElseCanView() throws Exception {
        mvc.perform(get("/api/promises").header(ApiController.VIEWER, "friend@example.com")).andExpect(status().isForbidden());
        mvc.perform(get("/api/promises").header(ApiController.VIEWER, "ravi")).andExpect(status().isForbidden());
    }
}
