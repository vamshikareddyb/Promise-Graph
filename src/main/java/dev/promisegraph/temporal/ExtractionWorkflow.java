package dev.promisegraph.temporal;

import dev.promisegraph.ingestion.ExtractionInput;
import dev.promisegraph.temporal.Contracts.ExtractionResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** One batch of messages becomes commitments, which start or signal promise workflows. */
@WorkflowInterface
public interface ExtractionWorkflow {

    @WorkflowMethod
    ExtractionResult run(ExtractionInput input);
}
