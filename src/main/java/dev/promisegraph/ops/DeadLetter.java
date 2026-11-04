package dev.promisegraph.ops;

import dev.promisegraph.domain.Message;
import java.util.List;

/**
 * Work that could not finish on its own and needs a person: a batch whose extraction kept failing,
 * or a promise whose workflow and stored state disagree. Also records what the sweep fixed by
 * itself, so the operations page shows both.
 *
 * @param id deterministic, so the same problem is recorded once
 * @param kind extraction_failed | out_of_sync | restarted
 * @param reference the workflow or promise it is about
 * @param messages for a failed batch: the messages to try again
 * @param status open | retried | dismissed | auto_fixed
 */
public record DeadLetter(String tenant, String id, String kind, String reference, String error, List<Message> messages,
        String createdAt, String status) {

    public DeadLetter {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public DeadLetter withStatus(String s) {
        return new DeadLetter(tenant, id, kind, reference, error, messages, createdAt, s);
    }
}
