package dev.promisegraph.temporal;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.temporal.Contracts.ExtractRequest;
import io.temporal.activity.ActivityInterface;
import java.util.List;

/** Model calls, on their own task queue so rate limits never block other work. */
@ActivityInterface
public interface ModelActivities {

    List<Commitment> extractCommitments(ExtractRequest request);
}
