package dev.promisegraph.eval;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.CommitmentKind;
import dev.promisegraph.extraction.Extractor;
import dev.promisegraph.extraction.RuleBasedExtractor;
import dev.promisegraph.extraction.model.AnthropicExtractor;
import dev.promisegraph.sim.Company;
import dev.promisegraph.sim.Dataset;
import dev.promisegraph.sim.Dataset.LabelledMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gate 1: how well do we find commitments? Run with {@code ./gradlew evalGate}.
 *
 * <ul>
 *   <li>detection: did we find a commitment where there is one (precision, recall)</li>
 *   <li>kind: for correct detections, was it new, slip or done</li>
 *   <li>due date: for correct detections with a labelled date, was the date right. Slips stated as a
 *       length ("by two weeks") are resolved later against the promise and reported separately.</li>
 * </ul>
 * Exits non-zero if precision is below the gate, so the build fails.
 */
public final class ExtractionEval {

    public static final class Report {
        public int tp;
        public int fp;
        public int fn;
        public int tn;
        public int kindCorrect;
        public int dueScored;
        public int dueCorrect;
        public int dueDeferred;
        public final List<String> errors = new ArrayList<>();

        public double precision() {
            return tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        }

        public double recall() {
            return tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        }

        public double kindAccuracy() {
            return tp == 0 ? 0 : (double) kindCorrect / tp;
        }

        public double dueAccuracy() {
            return dueScored == 0 ? 0 : (double) dueCorrect / dueScored;
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("messages        %d%n".formatted(tp + fp + fn + tn));
            sb.append("precision       %.3f  (%d correct, %d false alarms)%n".formatted(precision(), tp, fp));
            sb.append("recall          %.3f  (%d missed)%n".formatted(recall(), fn));
            sb.append("kind accuracy   %.3f%n".formatted(kindAccuracy()));
            sb.append("due accuracy    %.3f  (%d scored, %d resolved later)%n".formatted(dueAccuracy(), dueScored, dueDeferred));
            if (!errors.isEmpty()) {
                sb.append("%nfirst errors:%n".formatted());
                errors.stream().limit(15).forEach(e -> sb.append("  ").append(e).append(System.lineSeparator()));
            }
            return sb.toString();
        }
    }

    private ExtractionEval() {}

    public static Report evaluate(List<LabelledMessage> rows, Extractor extractor, Set<String> directory) {
        Report r = new Report();
        for (LabelledMessage row : rows) {
            List<Commitment> found = extractor.extract(List.of(row.message()), directory);
            Commitment predicted = found.isEmpty() ? null : found.get(0);
            String labelKind = row.label().kind();
            String text = row.message().text();
            String id = row.message().id();
            if (predicted != null && labelKind != null) {
                r.tp++;
                if (predicted.kind() == CommitmentKind.valueOf(labelKind.toUpperCase(Locale.ROOT))) {
                    r.kindCorrect++;
                } else {
                    r.errors.add("KIND %s: got %s, want %s: %s".formatted(id, predicted.kind(), labelKind, text));
                }
                String labelDue = row.label().due();
                if (labelDue != null) {
                    if (predicted.due() == null && predicted.shiftDays() != null) {
                        r.dueDeferred++;
                    } else {
                        r.dueScored++;
                        if (labelDue.equals(predicted.due())) {
                            r.dueCorrect++;
                        } else {
                            r.errors.add("DUE %s: got %s, want %s: %s".formatted(id, predicted.due(), labelDue, text));
                        }
                    }
                }
            } else if (predicted != null) {
                r.fp++;
                r.errors.add("FALSE ALARM %s: %s".formatted(id, text));
            } else if (labelKind != null) {
                r.fn++;
                r.errors.add("MISSED %s: %s".formatted(id, text));
            } else {
                r.tn++;
            }
        }
        return r;
    }

    public static void main(String[] args) {
        double minPrecision = 0.85;
        String which = "rules";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--min-precision".equals(args[i])) {
                minPrecision = Double.parseDouble(args[i + 1]);
            } else if ("--extractor".equals(args[i])) {
                which = args[i + 1];
            }
        }
        Extractor extractor = "llm".equals(which)
                ? new AnthropicExtractor(System.getenv("ANTHROPIC_API_KEY"), System.getenv().getOrDefault("PG_MODEL", "claude-sonnet-5-5"), 2000)
                : new RuleBasedExtractor();

        Report report = evaluate(Dataset.load("demo"), extractor, Company.directory());
        System.out.print(report.render());
        if (report.precision() < minPrecision) {
            System.out.printf("%nGATE FAILED: precision %.3f < %.2f%n", report.precision(), minPrecision);
            System.exit(1);
        }
        System.out.printf("%nGATE PASSED: precision %.3f >= %.2f%n", report.precision(), minPrecision);
    }
}
