package dev.promisegraph.temporal;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.TimingPolicy;
import java.util.List;
import java.util.Map;

/** Inputs and outputs that cross Temporal boundaries. Plain records, serialised by Temporal's Jackson converter. */
public final class Contracts {

    private Contracts() {}

    public record ExtractRequest(String tenant, List<Message> messages) {}

    public record ResolveRequest(String tenant, List<Commitment> commitments, String now) {}

    /**
     * Something that happened to a promise. {@code sourceId} makes each signal idempotent:
     * a message ID for chat events, a click ID for button presses.
     *
     * @param kind confirm | dismiss | update | slip | kept | cancel
     */
    public record PromiseSignal(String kind, String sourceId, String actor, String due, String what, String quote, String channel) {

        public static PromiseSignal of(String kind, String sourceId, String actor) {
            return new PromiseSignal(kind, sourceId, actor, null, null, null, "");
        }
    }

    /** @param type start | signal */
    public record Action(String type, String promiseId, Promise promise, PromiseSignal signal) {}

    public record DispatchRequest(String tenant, List<Action> actions) {}

    public record RecordRequest(Promise promise, String eventKind, String actor, Map<String, String> detail) {}

    public record DigestRequest(String tenant, String now, int horizonDays) {}

    public record ExtractionResult(int received, int claimed, int commitments, int actions) {}

    /** @param started true after continue-as-new: skip the opening steps */
    public record PromiseWorkflowInput(
            Promise promise,
            TimingPolicy policy,
            double confidenceThreshold,
            boolean started,
            List<String> sentKeys,
            List<String> applied) {

        public PromiseWorkflowInput {
            sentKeys = sentKeys == null ? List.of() : List.copyOf(sentKeys);
            applied = applied == null ? List.of() : List.copyOf(applied);
        }

        public static PromiseWorkflowInput fresh(Promise promise, TimingPolicy policy, double threshold) {
            return new PromiseWorkflowInput(promise, policy, threshold, false, List.of(), List.of());
        }
    }
}
