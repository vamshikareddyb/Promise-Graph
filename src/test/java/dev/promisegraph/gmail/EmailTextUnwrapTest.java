package dev.promisegraph.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Gmail's plain text is wrapped at about 76 characters; a sentence must read as one again. */
class EmailTextUnwrapTest {

    @Test
    void wrappedLinesAreJoinedButShortLinesAreKept() {
        String plain = "We have a meeting with the client on 07/11/2026. I want you to keep a\r\n"
                + "checklist regarding the client requirement checklist and keep me posted\r\nabout it.\r\n";
        assertThat(EmailText.clean(plain, null)).isEqualTo("We have a meeting with the client on 07/11/2026. I want you to keep a "
                + "checklist regarding the client requirement checklist and keep me posted about it.");
        assertThat(EmailText.clean("Hi Aswini,\n\nYou need to submit the report by friday\nthanks", null))
                .isEqualTo("Hi Aswini,\n\nYou need to submit the report by friday\nthanks");
    }
}
