package dev.promisegraph.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.CommitmentKind;
import dev.promisegraph.domain.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class OllamaTitlerTest {

    @Test
    void keepsOneCleanLineFromTheModel() {
        assertThat(OllamaTitler.clean("Title: \"send Vamshika the detailed requirements document.\"\n\nThis title names the document."))
                .contains("Send Vamshika the detailed requirements document");
        assertThat(OllamaTitler.clean("<think>the user wants a title</think>\n**Keep a checklist for the client meeting**"))
                .contains("Keep a checklist for the client meeting");
    }

    @Test
    void rejectsAnswersThatAreNotTitles() {
        assertThat(OllamaTitler.clean("")).isEmpty();
        assertThat(OllamaTitler.clean("Send")).isEmpty();
        assertThat(OllamaTitler.clean("Here is a title for the promise you described in the email above, written as you asked me to")).isEmpty();
    }

    @Test
    void promptSaysWhoOwesIt() {
        Message m = new Message("m1", "email:t1", "ashwini@example.com", "2026-11-08T10:00:00Z",
                "Could you please send over the detailed document containing the specified requirements by tomorrow?", null);
        Commitment request = new Commitment(CommitmentKind.NEW, "m1", Requests.ADDRESSEE, "send over the detailed document",
                "Could you please send over the detailed document containing the specified requirements by tomorrow?", 0.7,
                "email:t1", "ashwini@example.com", "2026-11-09", null);
        assertThat(OllamaTitler.prompt(request, m)).contains("from ashwini@example.com").contains("reader of the email was asked");
    }

    @Test
    void keepsTheRuleTitleWhenOllamaIsNotRunning() {
        Message m = new Message("m1", "email:t1", "a@example.com", "2026-11-08T10:00:00Z", "You have to attend a meeting today at 4.", null);
        Commitment c = new Commitment(CommitmentKind.NEW, "m1", Requests.ADDRESSEE, "attend a meeting",
                "You have to attend a meeting today at 4.", 0.7, "email:t1", "a@example.com", "2026-11-08", null);
        OllamaTitler titler = new OllamaTitler("http://127.0.0.1:1", "gemma4:e4b");
        assertThat(titler.retitle(List.of(c, c), List.of(m), x -> true)).containsExactly(c, c);
    }
}
