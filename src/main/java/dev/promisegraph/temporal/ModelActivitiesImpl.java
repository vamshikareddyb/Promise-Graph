package dev.promisegraph.temporal;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.extraction.ExtractionOutputException;
import dev.promisegraph.extraction.Extractor;
import dev.promisegraph.extraction.model.AnthropicExtractor.ModelRequestException;
import dev.promisegraph.temporal.Contracts.ExtractRequest;
import io.temporal.failure.ApplicationFailure;
import java.util.List;
import java.util.Set;

public final class ModelActivitiesImpl implements ModelActivities {

    private final Extractor extractor;
    private final Set<String> directory;

    public ModelActivitiesImpl(Extractor extractor, Set<String> directory) {
        this.extractor = extractor;
        this.directory = Set.copyOf(directory);
    }

    @Override
    public List<Commitment> extractCommitments(ExtractRequest request) {
        try {
            return extractor.extract(request.messages(), directory);
        } catch (ExtractionOutputException e) {
            // Retryable: a second model call usually returns valid output.
            throw ApplicationFailure.newFailureWithCause(e.getMessage(), "ExtractionOutputException", e);
        } catch (ModelRequestException e) {
            if (e.retryable()) {
                throw ApplicationFailure.newFailureWithCause(e.getMessage(), "ModelUnavailable", e);
            }
            throw ApplicationFailure.newNonRetryableFailureWithCause(e.getMessage(), "ModelRequestInvalid", e);
        }
    }
}
