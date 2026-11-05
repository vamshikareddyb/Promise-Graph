package dev.promisegraph.drafts;

import dev.promisegraph.drafts.DraftContracts.DraftRequest;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * The reply-draft agent for one email: gather the thread and the promises around it, have Claude
 * write the reply, save it for the person to review. Never sends anything.
 */
@WorkflowInterface
public interface DraftReplyWorkflow {

    /** @return "drafted", or why nothing was drafted */
    @WorkflowMethod
    String run(DraftRequest request);
}
