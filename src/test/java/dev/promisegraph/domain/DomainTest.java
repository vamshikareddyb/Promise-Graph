package dev.promisegraph.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.promisegraph.domain.Matching.Action;
import dev.promisegraph.domain.Matching.Resolution;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DomainTest {

    private static final String NOW = "2026-11-02T05:00:00Z";

    @Test
    void idsAreDeterministicAndNormalised() {
        assertThat(Ids.promiseId("demo", "m1", "Neha", "Ship the API!"))
                .isEqualTo(Ids.promiseId("demo", "m1", "neha", "ship the   api"))
                .startsWith("demo-");
        assertThat(Ids.normalize("It’s  DONE.")).isEqualTo("it s done");
        assertThat(Ids.extractionWorkflowId("demo", List.of("b", "a"))).isEqualTo(Ids.extractionWorkflowId("demo", List.of("a", "b")));
    }

    @Test
    void lifecycle() {
        assertThat(Lifecycle.transition(PromiseStatus.DETECTED, PromiseStatus.ACTIVE)).isEqualTo(PromiseStatus.ACTIVE);
        assertThat(Lifecycle.canTransition(PromiseStatus.AT_RISK, PromiseStatus.ACTIVE)).isTrue(); // renegotiated
        assertThatThrownBy(() -> Lifecycle.transition(PromiseStatus.KEPT, PromiseStatus.ACTIVE))
                .isInstanceOf(Lifecycle.InvalidTransitionException.class);
        assertThat(Lifecycle.canTransition(PromiseStatus.DETECTED, PromiseStatus.BROKEN)).isFalse();
        assertThat(Lifecycle.isTerminal(PromiseStatus.KEPT)).isTrue();
        assertThat(Lifecycle.isTerminal(PromiseStatus.AT_RISK)).isFalse();
    }

    @Test
    void timing() {
        TimingPolicy policy = TimingPolicy.defaults();
        LocalDate due = LocalDate.of(2026, 11, 13);
        List<Instant> times = Timing.reminderTimes(due, policy);
        assertThat(times).containsExactly(Instant.parse("2026-11-10T04:00:00Z"), Instant.parse("2026-11-12T04:00:00Z"));
        Instant now = Instant.parse("2026-11-11T00:00:00Z");
        assertThat(Timing.nextReminder(due, now, Set.of(), policy)).contains(times.get(1));
        assertThat(Timing.nextReminder(due, now, Set.of(times.get(1).toString()), policy)).isEmpty();
        assertThat(Timing.brokenDeadline(due, policy)).isEqualTo(Instant.parse("2026-11-15T12:00:00Z"));
    }

    @Nested
    class MatchingRules {

        private Promise promise(String what, String id, PromiseStatus status) {
            return new Promise(id, "demo", "neha", what, status, 0.9, NOW, NOW, null, "2026-11-13", "", List.of(), 0);
        }

        private Commitment c(CommitmentKind kind, String what, String owner, Integer shift) {
            return new Commitment(kind, "m2", owner, what, what, 0.9, "platform", null, null, shift);
        }

        @Test
        void stemmingMatchesTenses() {
            assertThat(Matching.tokens("shared the field list")).isEqualTo(Matching.tokens("share the field list"));
            assertThat(Matching.overlap("sent the deck", "send the pricing deck")).isEqualTo(1.0);
        }

        @Test
        void newStartsPromiseAndRestatementUpdates() {
            Resolution fresh = Matching.resolve(c(CommitmentKind.NEW, "send the deck", "neha", null), List.of(), "demo", NOW, Set.of());
            assertThat(fresh.action()).isEqualTo(Action.START);
            assertThat(fresh.promise().status()).isEqualTo(PromiseStatus.DETECTED);

            Resolution restated = Matching.resolve(c(CommitmentKind.NEW, "ship the data export API", "neha", null),
                    List.of(promise("ship the data export API", "p1", PromiseStatus.ACTIVE)), "demo", NOW, Set.of());
            assertThat(restated.action()).isEqualTo(Action.UPDATE);
            assertThat(restated.promiseId()).isEqualTo("p1");
        }

        @Test
        void retryAfterCreateStillStarts() {
            String pid = Ids.promiseId("demo", "m2", "neha", "send the deck");
            Resolution r = Matching.resolve(c(CommitmentKind.NEW, "send the deck", "neha", null),
                    List.of(promise("send the deck", pid, PromiseStatus.DETECTED)), "demo", NOW, Set.of(pid));
            assertThat(r.action()).isEqualTo(Action.START);
            assertThat(r.promiseId()).isEqualTo(pid);
        }

        @Test
        void slipShiftsFromCurrentDue() {
            Resolution r = Matching.resolve(c(CommitmentKind.SLIP, "The data export API", "neha", 14),
                    List.of(promise("ship the data export API", "p1", PromiseStatus.ACTIVE)), "demo", NOW, Set.of());
            assertThat(r.action()).isEqualTo(Action.SLIP);
            assertThat(r.due()).isEqualTo("2026-11-27");
        }

        @Test
        void followUpsOnlyMatchSameOwnerAndOpenPromises() {
            List<Promise> open = List.of(promise("send the deck", "p1", PromiseStatus.ACTIVE));
            assertThat(Matching.resolve(c(CommitmentKind.DONE, "sent the deck", "neha", null), open, "demo", NOW, Set.of()).action())
                    .isEqualTo(Action.KEPT);
            assertThat(Matching.resolve(c(CommitmentKind.DONE, "sent the deck", "ravi", null), open, "demo", NOW, Set.of()).action())
                    .isEqualTo(Action.IGNORE);
            List<Promise> closed = List.of(promise("send the deck", "p1", PromiseStatus.KEPT));
            assertThat(Matching.resolve(c(CommitmentKind.DONE, "sent the deck", "neha", null), closed, "demo", NOW, Set.of()).action())
                    .isEqualTo(Action.IGNORE);
        }
    }
}
