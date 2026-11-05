package dev.promisegraph.temporal;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.Message;
import dev.promisegraph.extraction.ExtractionOutputException;
import dev.promisegraph.extraction.Extractor;
import dev.promisegraph.extraction.Requests;
import dev.promisegraph.extraction.model.AnthropicExtractor.ModelRequestException;
import dev.promisegraph.temporal.Contracts.ExtractRequest;
import io.temporal.failure.ApplicationFailure;
import java.util.ArrayList;
import java.util.HashSet;
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
            return withRequests(request.messages(), extractor.extract(request.messages(), directory));
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

    /**
     * Adds requests made to the reader ("you need to submit the report by Friday") for emails in
     * which the extractor found nothing of the sender's own. Works the same for either extractor.
     */
    static List<Commitment> withRequests(List<Message> messages, List<Commitment> found) {
        Set<String> covered = new HashSet<>();
        found.forEach(c -> covered.add(c.messageId()));
        List<Commitment> out = new ArrayList<>(found);
        for (Message m : messages) {
            if (!covered.contains(m.id())) {
                Requests.find(m).ifPresent(out::add);
            }
        }
        return out;
    }
}
