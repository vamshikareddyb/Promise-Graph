package dev.promisegraph.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.Matching;
import dev.promisegraph.domain.Matching.Resolution;
import dev.promisegraph.domain.Person;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.extraction.RuleBasedExtractor;
import dev.promisegraph.sim.Company;
import dev.promisegraph.sim.Dataset;
import dev.promisegraph.sim.Dataset.LabelledMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DatasetAndEvalTest {

    private final List<LabelledMessage> rows = Dataset.load("demo");

    @Test
    void companyAndDatasetShape() {
        assertThat(Company.PEOPLE).hasSize(20);
        assertThat(Company.PEOPLE.stream().map(Person::team).collect(Collectors.toSet())).containsExactlyInAnyOrderElementsOf(Company.TEAMS);
        assertThat(Company.PEOPLE.stream().filter(Person::lead)).hasSize(Company.TEAMS.size());
        assertThat(rows).hasSize(300);
        assertThat(rows).allSatisfy(r -> assertThat(Company.directory()).contains(r.message().sender()));
    }

    @Test
    void gate1PassesOnSimulatedData() {
        ExtractionEval.Report report = ExtractionEval.evaluate(rows, new RuleBasedExtractor(), Company.directory());
        assertThat(report.precision()).isGreaterThanOrEqualTo(0.85);
    }

    /** The slip and completion messages in the hand-written core scenario find the right promises. */
    @Test
    void coreStoryResolvesEndToEnd() {
        RuleBasedExtractor extractor = new RuleBasedExtractor();
        Map<String, Promise> promises = new LinkedHashMap<>();
        List<String> outcomes = new ArrayList<>();
        for (LabelledMessage row : rows) {
            if (!row.message().id().startsWith("core-")) {
                continue;
            }
            for (Commitment c : extractor.extract(List.of(row.message()), Company.directory())) {
                List<Promise> open = promises.values().stream().filter(Promise::stillOpen).toList();
                Resolution r = Matching.resolve(c, open, "demo", row.message().ts(), promises.keySet());
                switch (r.action()) {
                    case START -> {
                        if (r.promise() != null) {
                            promises.put(r.promise().id(), r.promise());
                        }
                    }
                    case KEPT -> promises.computeIfPresent(r.promiseId(), (k, p) -> p.withStatus(PromiseStatus.KEPT));
                    case SLIP -> promises.computeIfPresent(r.promiseId(), (k, p) -> p.withDue(r.due()).withStatus(PromiseStatus.AT_RISK));
                    default -> { }
                }
                outcomes.add(c.owner() + ":" + r.action());
            }
        }
        Promise exportApi = promises.values().stream().filter(p -> p.what().equals("ship the data export API")).findFirst().orElseThrow();
        assertThat(exportApi.due()).isEqualTo("2026-11-27");
        assertThat(exportApi.status()).isEqualTo(PromiseStatus.AT_RISK);
        assertThat(outcomes).contains("leela:KEPT", "arjun:KEPT", "meera:SLIP", "meera:KEPT");
        assertThat(outcomes).noneMatch(o -> o.endsWith(":IGNORE"));
    }
}
