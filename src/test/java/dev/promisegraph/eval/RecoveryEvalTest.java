package dev.promisegraph.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.recovery.RulePlanner;
import org.junit.jupiter.api.Test;

class RecoveryEvalTest {

    @Test
    void gateThreePassesWithTheRulePlanner() {
        RecoveryEval.Report report = RecoveryEval.evaluate(new RulePlanner());
        assertThat(report.integrityProblems()).isEmpty();
        assertThat(report.badCaught()).isEqualTo(report.badTotal());
        assertThat(report.approvalRate()).isGreaterThanOrEqualTo(RecoveryEval.MIN_APPROVAL_RATE);
        assertThat(report.passed()).isTrue();
        // A full team can't supply a helper, so that lead's rule can't be met: an honest miss, not a bug.
        assertThat(report.outcomes()).filteredOn(o -> o.scenario().equals("team full, wants help"))
                .singleElement().satisfies(o -> assertThat(o.approved()).isFalse());
    }
}
