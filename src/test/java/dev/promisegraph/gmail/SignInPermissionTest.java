package dev.promisegraph.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Google lets people untick single permissions; the app has to notice when Gmail is one of them. */
class SignInPermissionTest {

    @Test
    void gmailMustBeAmongTheGrantedPermissions() {
        String all = "openid https://www.googleapis.com/auth/userinfo.email " + GoogleOAuth.SCOPE + " https://www.googleapis.com/auth/userinfo.profile";
        assertThat(new GoogleOAuth.SignIn("a@x.com", "A", "r", all).canReadGmail()).isTrue();
        assertThat(new GoogleOAuth.SignIn("a@x.com", "A", "r", "openid https://www.googleapis.com/auth/userinfo.email").canReadGmail()).isFalse();
        assertThat(new GoogleOAuth.SignIn("a@x.com", "A", "r", "").canReadGmail()).isFalse();
        assertThat(new GoogleOAuth.SignIn("a@x.com", "A", "r", null).canReadGmail()).isFalse();
    }

    @Test
    void googlesReasonIsKeptInTheError() {
        assertThat(GmailRestApi.reason("{\"error\":{\"code\":403,\"message\":\"Request had insufficient authentication scopes.\"}}"))
                .isEqualTo(": Request had insufficient authentication scopes.");
        assertThat(GmailRestApi.reason("not json")).isEmpty();
        assertThat(GmailRestApi.reason("{}")).isEmpty();
    }
}
