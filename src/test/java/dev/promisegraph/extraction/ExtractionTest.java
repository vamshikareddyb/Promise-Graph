package dev.promisegraph.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.CommitmentKind;
import dev.promisegraph.domain.Message;
import dev.promisegraph.extraction.model.ModelOutputParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExtractionTest {

    @Nested
    class Filter {

        @ParameterizedTest
        @ValueSource(strings = {
                "Can someone send the deck by Friday?",
                "Maybe we could ship it next week?",
                "I might fix it if I get time.",
                "We need to fix the bug by Friday.",
                "Ravi said they would review it.",
                "Heads up, I'm on leave from 16 Nov to 27 Nov.",
                "I'll be out tomorrow.",
                "Not sure we can ship by Friday, let's discuss.",
                "Thanks, that helps a lot!"})
        void ignoresNonCommitments(String text) {
            assertThat(Detect.looksLikeCommitment(text)).isFalse();
        }

        @Test
        void detectsKinds() {
            assertThat(Detect.candidates("I'll send the deck by Friday.").get(0).kind()).isEqualTo(CommitmentKind.NEW);
            assertThat(Detect.candidates("The API will slip to 20 Nov.").get(0).kind()).isEqualTo(CommitmentKind.SLIP);
            assertThat(Detect.candidates("Done: sent the deck.").get(0).kind()).isEqualTo(CommitmentKind.DONE);
            assertThat(Detect.candidates("I've reviewed the plan.").get(0).kind()).isEqualTo(CommitmentKind.DONE);
            assertThat(Detect.candidates("I'll build the job by 18 Nov, once the API is live.").get(0).kind())
                    .isEqualTo(CommitmentKind.NEW);
        }

        @Test
        void quoteIsExactSubstring() {
            String text = "Globex asked again. I'll confirm a date with them by tomorrow.";
            Detect.Candidate c = Detect.candidates(text).get(0);
            assertThat(text).contains(c.sentence());
            assertThat(c.sentence()).startsWith("I'll confirm");
        }
    }

    @Nested
    class Rules {

        private Commitment extract(String text) {
            List<Commitment> out = new RuleBasedExtractor().extract(
                    List.of(new Message("m1", "platform", "neha", "2026-11-02T10:00:00+05:30", text, "demo")), Set.of());
            return out.isEmpty() ? null : out.get(0);
        }

        @Test
        void newWithDate() {
            Commitment c = extract("I'll ship the data export API by 13 Nov so analytics can start.");
            assertThat(c.kind()).isEqualTo(CommitmentKind.NEW);
            assertThat(c.what()).isEqualTo("ship the data export API");
            assertThat(c.due()).isEqualTo("2026-11-13");
            assertThat(c.owner()).isEqualTo("neha");
            assertThat(c.confidence()).isGreaterThanOrEqualTo(0.8);
        }

        @Test
        void newWithoutDateHasLowConfidence() {
            Commitment c = extract("I'll look at the flaky test.");
            assertThat(c.due()).isNull();
            assertThat(c.confidence()).isLessThan(0.8);
        }

        @Test
        void slips() {
            Commitment dated = extract("The schema decision will slip to 11 Nov, we're waiting on types.");
            assertThat(dated.what()).isEqualTo("The schema decision");
            assertThat(dated.due()).isEqualTo("2026-11-11");

            Commitment shifted = extract("The data export API will slip by two weeks.");
            assertThat(shifted.due()).isNull();
            assertThat(shifted.shiftDays()).isEqualTo(14);
        }

        @Test
        void done() {
            Commitment c = extract("Done: shared the export field list with analytics.");
            assertThat(c.kind()).isEqualTo(CommitmentKind.DONE);
            assertThat(c.what()).isEqualTo("shared the export field list with analytics");
        }

        @Test
        void questionYieldsNothing() {
            assertThat(extract("Can you keep the API backwards compatible?")).isNull();
        }
    }

    @Nested
    class ModelOutput {

        private final Message msg = new Message("m1", "platform", "neha", "2026-11-02T10:00:00+05:30",
                "I'll ship the export API by 13 Nov.", "demo");
        private final Set<String> directory = Set.of("neha", "ravi");

        private String payload(String overrides) {
            return """
                    {"commitments": [{"message_id": "m1", "kind": "new", "owner": "neha",
                      "what": "ship the export API", "quote": "I'll ship the export API by 13 Nov.",
                      "confidence": 0.9, "due": "2026-11-13" %s}]}""".formatted(overrides);
        }

        @Test
        void validOutputAndFences() {
            assertThat(ModelOutputParser.parse(payload(""), List.of(msg), directory)).singleElement()
                    .satisfies(c -> assertThat(c.due()).isEqualTo("2026-11-13"));
            assertThat(ModelOutputParser.parse("```json\n" + payload("") + "\n```", List.of(msg), directory)).hasSize(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"not json", "{\"commitments\": [{\"kind\": \"new\"}]}", "{\"other\": 1}"})
        void malformedOutputThrows(String raw) {
            assertThatThrownBy(() -> ModelOutputParser.parse(raw, List.of(msg), directory))
                    .isInstanceOf(ExtractionOutputException.class);
        }

        @Test
        void confidenceOutOfRangeThrows() {
            assertThatThrownBy(() -> ModelOutputParser.parse(payload("").replace("0.9", "1.5"), List.of(msg), directory))
                    .isInstanceOf(ExtractionOutputException.class);
        }

        @Test
        void ungroundedOrUnknownItemsAreDropped() {
            String ungrounded = payload("").replace("\"I'll ship the export API by 13 Nov.\"", "\"I'll wire you money\"");
            assertThat(ModelOutputParser.parse(ungrounded, List.of(msg), directory)).isEmpty();
            assertThat(ModelOutputParser.parse(payload("").replace("\"m1\"", "\"m9\""), List.of(msg), directory)).isEmpty();
        }

        @Test
        void unknownOwnerFallsBackToSender() {
            String other = payload("").replace("\"owner\": \"neha\"", "\"owner\": \"mallory\"");
            assertThat(ModelOutputParser.parse(other, List.of(msg), directory)).singleElement()
                    .satisfies(c -> assertThat(c.owner()).isEqualTo("neha"));
        }
    }
}
