package dev.promisegraph.web;

import dev.promisegraph.config.AuthProperties;
import dev.promisegraph.config.GmailConfig;
import dev.promisegraph.config.GmailProperties;
import dev.promisegraph.config.PromiseGraphProperties;
import dev.promisegraph.gmail.GmailApi;
import dev.promisegraph.gmail.GmailAuthorize;
import dev.promisegraph.gmail.GoogleOAuth;
import dev.promisegraph.ops.AuditEntry;
import dev.promisegraph.store.AuditLog;
import dev.promisegraph.team.Member;
import dev.promisegraph.team.SecretBox;
import dev.promisegraph.team.TeamMailboxes;
import dev.promisegraph.team.TeamStore;
import io.temporal.client.WorkflowClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Team mode: "Sign in with Google". One consent covers who you are and read-only Gmail, so signing
 * in also starts reading your mailbox. The first person ever to sign in becomes an admin.
 */
@RestController
@ConditionalOnProperty(prefix = "promisegraph.auth", name = "mode", havingValue = "google")
public class AuthController {

    /** The session attribute holding the signed-in email address. */
    public static final String USER = "pg.user";
    private static final String STATE = "pg.oauth.state";
    private static final String VERIFIER = "pg.oauth.verifier";
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthProperties auth;
    private final GoogleOAuth.ClientSecrets secrets;
    private final TeamStore team;
    private final SecretBox box;
    private final TeamMailboxes mailboxes;
    private final WorkflowClient client;
    private final GmailProperties gmail;
    private final PromiseGraphProperties props;
    private final AuditLog audit;

    public AuthController(AuthProperties auth, GoogleOAuth.ClientSecrets secrets, TeamStore team, SecretBox box, TeamMailboxes mailboxes,
            WorkflowClient client, GmailProperties gmail, PromiseGraphProperties props, AuditLog audit) {
        this.auth = auth;
        this.secrets = secrets;
        this.team = team;
        this.box = box;
        this.mailboxes = mailboxes;
        this.client = client;
        this.gmail = gmail;
        this.props = props;
        this.audit = audit;
    }

    @GetMapping("/login")
    public ResponseEntity<Void> login(HttpSession session) {
        String state = GmailAuthorize.random(24);
        String verifier = GmailAuthorize.random(48);
        session.setAttribute(STATE, state);
        session.setAttribute(VERIFIER, verifier);
        String url = GoogleOAuth.webSignInUrl(secrets, auth.redirectUri(), state, GmailAuthorize.challenge(verifier));
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    @GetMapping("/login/callback")
    public ResponseEntity<String> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
            @RequestParam(required = false) String error, HttpServletRequest request) {
        HttpSession session = request.getSession();
        Object expected = session.getAttribute(STATE);
        Object verifier = session.getAttribute(VERIFIER);
        session.removeAttribute(STATE);
        session.removeAttribute(VERIFIER);
        if (error != null) {
            return page(HttpStatus.BAD_REQUEST, "Google sign-in was cancelled or refused (" + error + ").");
        }
        if (code == null || expected == null || !expected.equals(state) || verifier == null) {
            return page(HttpStatus.BAD_REQUEST, "That sign-in link has expired or didn't start here. Please sign in again.");
        }
        GoogleOAuth.SignIn signIn;
        try {
            signIn = new GoogleOAuth(secrets, () -> null).completeWebSignIn(code, auth.redirectUri(), verifier.toString());
        } catch (GmailApi.GmailApiException e) {
            log.warn("Google sign-in failed: {}", e.getMessage());
            return page(HttpStatus.BAD_GATEWAY, "Google sign-in failed: " + e.getMessage());
        }
        if (!signIn.canReadGmail()) {
            log.warn("{} signed in without allowing Gmail access", signIn.email());
            return page(HttpStatus.FORBIDDEN, "Promise Graph needs to read your Gmail to find your promises, but that permission wasn't "
                    + "allowed. Sign in again and, on Google's screen, tick the box for \"View your email messages and settings\" "
                    + "(or click \"Select all\").");
        }
        String tenant = props.tenant();
        String email = signIn.email().toLowerCase(java.util.Locale.ROOT);
        String now = Instant.now().toString();
        boolean first = team.members(tenant).isEmpty();
        Member me = team.addIfAbsent(new Member(tenant, email, signIn.name(), "", "", false, first, true, now));
        if (signIn.refreshToken() != null) {
            team.saveRefreshToken(tenant, email, box.encrypt(signIn.refreshToken()), now);
            mailboxes.forget(email);
        }
        if (props.temporal().workerEnabled() && team.refreshToken(tenant, email).isPresent()) {
            GmailConfig.startSync(client, gmail, tenant, email);
        }
        request.changeSessionId(); // a new session ID after signing in
        session.setAttribute(USER, me.email());
        audit.record(new AuditEntry(tenant, now, email, "sign_in", email, null));
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/")).build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<String> page(HttpStatus status, String message) {
        String html = "<!doctype html><meta charset=utf-8><title>Promise Graph sign-in</title>"
                + "<body style=\"font-family:system-ui;margin:40px\"><p>" + message.replace("<", "&lt;")
                + "</p><p><a href=\"/login\">Sign in again</a></p></body>";
        return ResponseEntity.status(status).header("Content-Type", "text/html; charset=utf-8").body(html);
    }
}
