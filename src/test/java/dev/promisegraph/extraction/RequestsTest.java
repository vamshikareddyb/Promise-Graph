package dev.promisegraph.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.Message;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Requests emailed to the reader, sent on Wednesday 4 Nov 2026. */
class RequestsTest {

    static Optional<Commitment> find(String text) {
        return Requests.find(new Message("m1", "email:thread-1", "boss@example.com", "2026-11-04T18:32:10Z", text, "demo"));
    }

    @Test
    void aRequestWithADeadlineBecomesSomethingTheReaderOwes() {
        Commitment c = find("You need to submit the project report by friday").orElseThrow();
        assertThat(c.owner()).isEqualTo(Requests.ADDRESSEE);
        assertThat(c.recipient()).isEqualTo("boss@example.com");
        assertThat(c.what()).isEqualTo("submit the project report");
        assertThat(c.due()).isEqualTo("2026-11-06");
        assertThat(c.confidence()).isLessThan(0.8); // the reader confirms it

        assertThat(find("Hi Aswini,\n\nCan you please review the slides by tomorrow?\n\nThanks").orElseThrow().what())
                .isEqualTo("review the slides");
        assertThat(find("Kindly submit your timesheet before 10 Nov.").orElseThrow().due()).isEqualTo("2026-11-10");
        assertThat(find("Please send the invoice by Monday.").orElseThrow().what()).isEqualTo("send the invoice");
    }

    @Test
    void politenessFirstPersonAndUndatedAsksAreNotRequests() {
        assertThat(find("Please find attached the report.")).isEmpty();
        assertThat(find("You should check it out sometime")).isEmpty();
        assertThat(find("I'll send the report by Friday.")).isEmpty();
    }

    @Test
    void onlyEmailsAreReadThisWay() {
        assertThat(Requests.find(new Message("m2", "platform", "neha", "2026-11-04T18:32:10Z", "You need to submit it by friday", "demo")))
                .isEmpty();
    }
}
