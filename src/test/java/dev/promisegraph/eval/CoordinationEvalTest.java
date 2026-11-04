package dev.promisegraph.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.sim.Dataset;
import org.junit.jupiter.api.Test;

class CoordinationEvalTest {

    @Test
    void gate2PassesOnTheCoreScenario() {
        CoordinationEval.Report report = CoordinationEval.evaluate(Dataset.load("demo"), 3);
        assertThat(report.passed()).as(report.render()).isTrue();
    }
}
