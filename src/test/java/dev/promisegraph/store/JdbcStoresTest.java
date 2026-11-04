package dev.promisegraph.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.promisegraph.domain.Evidence;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseEvent;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.inbox.InboxItem;
import dev.promisegraph.links.Link;
import dev.promisegraph.domain.Message;
import dev.promisegraph.ops.AuditEntry;
import dev.promisegraph.ops.DeadLetter;
import dev.promisegraph.recovery.Move;
import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.recovery.RecoveryPlan;
import java.sql.Connection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Postgres stores against a real database, with every migration applied in order.
 * Skipped when Docker is not available; CI always runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcStoresTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    static HikariDataSource ds;
    static NamedParameterJdbcTemplate jdbc;

    private static final String T0 = "2026-11-02T05:00:00Z";

    @BeforeAll
    static void migrate() throws Exception {
        ds = new HikariDataSource();
        ds.setJdbcUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource("migrations/001_init.sql"));
            ScriptUtils.executeSqlScript(c, new FileSystemResource("migrations/002_phase2.sql"));
            ScriptUtils.executeSqlScript(c, new FileSystemResource("migrations/002_phase2.sql")); // safe to re-run
            ScriptUtils.executeSqlScript(c, new FileSystemResource("migrations/003_phase3.sql"));
            ScriptUtils.executeSqlScript(c, new FileSystemResource("migrations/003_phase3.sql"));
            ScriptUtils.executeSqlScript(c, new FileSystemResource("migrations/004_phase4.sql"));
            ScriptUtils.executeSqlScript(c, new FileSystemResource("migrations/004_phase4.sql"));
        }
        jdbc = new NamedParameterJdbcTemplate(ds);
    }

    @AfterAll
    static void close() {
        ds.close();
    }

    private static Promise promise(String id, String owner, PromiseStatus status, int version) {
        return new Promise(id, "demo", owner, "send the deck", status, 0.9, T0, T0, null, "2026-11-13", "platform",
                List.of(new Evidence("m-" + id, "I'll send the deck by 13 Nov.", "platform")), version);
    }

    @Test
    void promiseRepository() {
        JdbcRepository repo = new JdbcRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        assertThat(repo.claimMessage("demo", "m1", "batch-a")).isTrue();
        assertThat(repo.claimMessage("demo", "m1", "batch-a")).isTrue();
        assertThat(repo.claimMessage("demo", "m1", "batch-b")).isFalse();

        repo.createPromise(promise("p1", "neha", PromiseStatus.DETECTED, 0));
        repo.createPromise(promise("p1", "someone-else", PromiseStatus.DETECTED, 0));
        assertThat(repo.getPromise("demo", "p1")).get().satisfies(p -> {
            assertThat(p.owner()).isEqualTo("neha");
            assertThat(p.due()).isEqualTo("2026-11-13");
            assertThat(p.evidence()).singleElement().extracting(Evidence::quote).isEqualTo("I'll send the deck by 13 Nov.");
        });

        assertThat(repo.savePromise(promise("p1", "nikhil", PromiseStatus.ACTIVE, 2))).isTrue(); // a handoff moved it
        assertThat(repo.savePromise(promise("p1", "neha", PromiseStatus.DETECTED, 1))).isFalse();
        assertThat(repo.getPromise("demo", "p1")).get().satisfies(p -> {
            assertThat(p.status()).isEqualTo(PromiseStatus.ACTIVE);
            assertThat(p.owner()).isEqualTo("nikhil");
        });
        assertThat(repo.openPromises("demo", List.of("nikhil"))).extracting(Promise::id).containsExactly("p1");
        assertThat(repo.promisesByStatus("demo", EnumSet.of(PromiseStatus.KEPT))).isEmpty();
        assertThat(repo.existingIds("demo", List.of("p1", "nope"))).containsExactly("p1");

        PromiseEvent e = new PromiseEvent("demo", "p1", "slipped", "neha", T0, "p1:2", Map.of("old_due", "2026-11-13"));
        assertThat(repo.appendEvent(e)).isTrue();
        assertThat(repo.appendEvent(e)).isFalse();
        assertThat(repo.events("demo", "p1")).singleElement().satisfies(ev -> assertThat(ev.detail()).containsEntry("old_due", "2026-11-13"));

        repo.recordNotification("demo", "k", "neha");
        repo.recordNotification("demo", "k", "neha");
        assertThat(repo.notificationSent("demo", "k")).isTrue();
    }

    @Test
    void links() {
        JdbcStores.Links links = new JdbcStores.Links(jdbc);
        Link l = new Link("demo", "api", "job", Link.Status.SUGGESTED, "the api is live", "rules", null, T0);
        assertThat(links.addLink(l)).isTrue();
        assertThat(links.addLink(l)).isFalse();
        assertThat(links.confirmedFrom("demo", List.of("api"))).isEmpty();
        assertThat(links.decide("demo", "api", "job", Link.Status.CONFIRMED, "dev")).isTrue();
        assertThat(links.confirmedFrom("demo", List.of("api"))).singleElement().satisfies(x -> {
            assertThat(x.to()).isEqualTo("job");
            assertThat(x.decidedBy()).isEqualTo("dev");
            assertThat(x.reason()).isEqualTo("the api is live");
        });
        assertThat(links.linksOf("demo", List.of("job"))).hasSize(1);
        assertThat(links.decide("demo", "nope", "job", Link.Status.REJECTED, "dev")).isFalse();
    }

    @Test
    void inbox() {
        JdbcStores.Inbox inbox = new JdbcStores.Inbox(jdbc);
        InboxItem item = new InboxItem("demo", "key-1", "dev", "Does it depend?", List.of("confirm link", "not related"),
                "job", "link:api>job", T0, "open", null);
        inbox.addItem(item);
        inbox.addItem(item);
        assertThat(inbox.inbox("demo", "dev", true)).singleElement().satisfies(i -> {
            assertThat(i.actions()).containsExactly("confirm link", "not related");
            assertThat(i.subject()).isEqualTo("link:api>job");
        });
        assertThat(inbox.resolve("demo", "key-1", "confirm link")).isTrue();
        assertThat(inbox.resolve("demo", "key-1", "not related")).isFalse(); // a double click acts once
        assertThat(inbox.inbox("demo", "dev", true)).isEmpty();
        assertThat(inbox.getItem("demo", "key-1")).get().extracting(InboxItem::resolvedAction).isEqualTo("confirm link");
        assertThat(inbox.recipients("demo")).contains("dev");
    }

    @Test
    void absencesAndHandoffs() {
        JdbcStores.Handoffs store = new JdbcStores.Handoffs(jdbc);
        Absence a = new Absence("demo", "karan", "2026-11-16", "2026-11-27", "leave", "core-015");
        assertThat(store.addAbsence(a)).isTrue();
        assertThat(store.addAbsence(a)).isFalse();
        assertThat(store.absences("demo")).containsExactly(a);

        HandoffRecord h = new HandoffRecord("demo", a.handoffId(), "karan", "2026-11-16", "2026-11-27", "leave", "awaiting_cover",
                "rules", List.of(new HandoffRecord.ItemState("faq", "write the launch FAQ", "2026-11-19", "brief", "nikhil", null,
                        "proposed", "same role")), T0);
        store.saveHandoff(h);
        store.saveHandoff(h.with("covered", List.of(h.items().get(0).accepted("nikhil")), "rules", "2026-11-13T04:00:00Z"));
        assertThat(store.getHandoff("demo", a.handoffId())).get().satisfies(x -> {
            assertThat(x.status()).isEqualTo("covered");
            assertThat(x.items()).singleElement().satisfies(i -> {
                assertThat(i.acceptedOwner()).isEqualTo("nikhil");
                assertThat(i.state()).isEqualTo("accepted");
            });
        });
        assertThat(store.handoffs("demo")).hasSize(1);
    }

    @Test
    void negotiationSnapshotsRoundTrip() {
        JdbcStores.Negotiations store = new JdbcStores.Negotiations(jdbc);
        Promise job = promise("job", "dev", PromiseStatus.AT_RISK, 2);
        RecoveryPlan plan = RecoveryPlan.draft("Accept the new dates", "move 1 date", List.of(Move.moveDate(job, "2026-12-02", "same gap")), "rules")
                .withId("A").verified(new RecoveryPlan.Score(1, 0, 0, 0, 14), List.of());
        NegotiationRecord n = new NegotiationRecord("demo", "api-v3", "api", "ship the data export API", "neha", "2026-11-13",
                "2026-11-27", "awaiting_approval", 1, "context", "rules", List.of(plan), List.of(), List.of("meera", "ravi"),
                Map.of("ravi", "A"), null, "2026-11-08T05:00:00Z", List.of("started"), T0, T0);
        store.save(n);
        store.save(n); // replacing the snapshot is safe to repeat

        NegotiationRecord back = store.get("demo", "api-v3").orElseThrow();
        assertThat(back).isEqualTo(n);
        assertThat(back.plans().get(0).passes()).isTrue();
        assertThat(store.openFor("demo", "api")).hasSize(1);

        NegotiationRecord applied = new NegotiationRecord("demo", "api-v3", "api", "ship the data export API", "neha", "2026-11-13",
                "2026-11-27", "applied", 1, "context", "rules", List.of(plan), List.of(), List.of("meera", "ravi"),
                Map.of("ravi", "A", "meera", "A"), "A", "2026-11-08T05:00:00Z", List.of("started", "applied"), T0, "2026-11-03T05:00:00Z");
        store.save(applied);
        assertThat(store.openFor("demo", "api")).isEmpty();
        assertThat(store.list("demo")).singleElement().extracting(NegotiationRecord::chosen).isEqualTo("A");
    }

    @Test
    void deadLettersAndAuditLog() {
        JdbcStores.DeadLetters dead = new JdbcStores.DeadLetters(jdbc);
        DeadLetter d = new DeadLetter("demo", "extraction:extract-1", "extraction_failed", "extract-1", "model unavailable",
                List.of(new Message("m1", "platform", "neha", "2026-11-02T10:05:00+05:30", "I'll ship it by 13 Nov.", "demo")), T0, "open");
        assertThat(dead.add(d)).isTrue();
        assertThat(dead.add(d)).isFalse();
        assertThat(dead.get("demo", d.id())).get().satisfies(back -> {
            assertThat(back.messages()).isEqualTo(d.messages());
            assertThat(back.error()).isEqualTo("model unavailable");
            assertThat(back.reference()).isEqualTo("extract-1");
        });
        assertThat(dead.list("demo", true)).hasSize(1);
        assertThat(dead.setStatus("demo", d.id(), "retried")).isTrue();
        assertThat(dead.list("demo", true)).isEmpty();
        assertThat(dead.list("demo", false)).singleElement().extracting(DeadLetter::status).isEqualTo("retried");

        JdbcStores.Audit audit = new JdbcStores.Audit(jdbc);
        audit.record(new AuditEntry("demo", T0, "ravi", "link:add", "a>b", null));
        audit.record(new AuditEntry("demo", "2026-11-02T06:00:00Z", "meera", "negotiation:approve B", "api-v3", null));
        assertThat(audit.recent("demo", 10)).extracting(AuditEntry::actor).containsExactly("meera", "ravi");
        assertThat(audit.recent("demo", 1)).hasSize(1);
    }
}
