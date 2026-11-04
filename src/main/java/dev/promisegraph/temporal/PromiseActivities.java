package dev.promisegraph.temporal;

import dev.promisegraph.ingestion.ExtractionInput;
import dev.promisegraph.notify.Notification;
import dev.promisegraph.temporal.Contracts.Action;
import dev.promisegraph.temporal.Contracts.ClaimResult;
import dev.promisegraph.temporal.Contracts.DigestRequest;
import dev.promisegraph.temporal.Contracts.DispatchRequest;
import dev.promisegraph.temporal.Contracts.RecordRequest;
import dev.promisegraph.temporal.Contracts.ResolveRequest;
import io.temporal.activity.ActivityInterface;
import java.util.List;

/** Activities on the main task queue. Each is safe to retry. */
@ActivityInterface
public interface PromiseActivities {

    /**
     * Drops messages another batch already handled, applies the cheap commitment filter, and
     * picks out planned absences.
     */
    ClaimResult claimMessages(ExtractionInput input);

    List<Action> resolveCommitments(ResolveRequest request);

    int dispatchActions(DispatchRequest request);

    void recordPromise(RecordRequest request);

    /** At most one send per key. Returns false if this key was already sent. */
    boolean sendNotification(Notification notification);

    int sendDigests(DigestRequest request);
}
