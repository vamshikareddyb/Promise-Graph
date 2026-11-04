package dev.promisegraph.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.domain.Message;
import org.junit.jupiter.api.Test;

class RedactorTest {

    @Test
    void removesContactDetailsCardsAndSecretsButKeepsDates() {
        assertThat(Redactor.redact("I'll send the deck to jane.doe@acme.com by 2026-11-20."))
                .isEqualTo("I'll send the deck to [email at acme.com] by 2026-11-20.");
        assertThat(Redactor.redact("Call me on +91 98765 43210 or 080-4123-4567 tomorrow"))
                .isEqualTo("Call me on [phone] or [phone] tomorrow");
        assertThat(Redactor.redact("card 4111 1111 1111 1111 for the trial")).isEqualTo("card [card number] for the trial");
        assertThat(Redactor.redact("the password is hunter2 and api key: sk-abcdefghijklmnop1234"))
                .isEqualTo("the password is [secret] and api key: [secret]");
        assertThat(Redactor.redact("token=ghp_abcdefghijklmnopqrstuvwxyz123456")).isEqualTo("token=[secret]");
        assertThat(Redactor.redact("I'll ship the export API by 13 Nov, ticket 12345."))
                .isEqualTo("I'll ship the export API by 13 Nov, ticket 12345.");
        assertThat(Redactor.redact("Due 20-11-2026, order placed 2026-11-13")).isEqualTo("Due 20-11-2026, order placed 2026-11-13");
    }

    @Test
    void messagesKeepEverythingButTheText() {
        Message m = new Message("m1", "email:t1", "aswini@example.com", "2026-11-02T10:00:00+05:30", "ping me at 98765 43210", "demo");
        Message r = Redactor.redact(m);
        assertThat(r.sender()).isEqualTo("aswini@example.com");
        assertThat(r.text()).isEqualTo("ping me at [phone]");
        Message clean = new Message("m2", "c", "s", "2026-11-02T10:00:00+05:30", "nothing to hide", "demo");
        assertThat(Redactor.redact(clean)).isSameAs(clean);
    }
}
