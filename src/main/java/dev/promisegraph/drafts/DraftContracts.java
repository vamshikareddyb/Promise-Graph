package dev.promisegraph.drafts;

import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.Message;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Inputs and outputs of the reply-draft agent. Everything is a plain record so it crosses Temporal boundaries as JSON. */
public final class DraftContracts {

    private DraftContracts() {}

    /** One draft workflow per email (plus a suffix when the person asks for a new version). */
    public static String workflowId(String tenant, String messageId) {
        return "draft-reply-" + tenant + "-" + messageId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Draft a reply to one email.
     *
     * @param message the email, as extraction saw it (already redacted)
     * @param instruction what the person asked for when regenerating ("shorter", "decline politely"), or null
     * @param replace true when regenerating: overwrite the existing draft
     */
    public record DraftRequest(String tenant, Message message, String instruction, boolean replace) {

        public static DraftRequest first(String tenant, Message message) {
            return new DraftRequest(tenant, message, null, false);
        }

        /**
         * One request per email that produced a promise or a request. Only emails: other sources have
         * nobody to reply to. Whether the email is worth answering (not your own, not already answered)
         * is decided later, by {@link DraftActivities#prepareDraft}.
         */
        public static List<DraftRequest> forEmails(String tenant, List<Message> messages, List<Commitment> commitments) {
            Set<String> withPromises = new HashSet<>();
            commitments.forEach(c -> withPromises.add(c.messageId()));
            List<DraftRequest> out = new ArrayList<>();
            for (Message m : messages) {
                if (EmailRef.of(m).isPresent() && withPromises.contains(m.id())) {
                    out.add(first(tenant, m));
                }
            }
            return out;
        }
    }

    /** One email of the thread, oldest first. {@code mine} marks what the person themselves wrote. */
    public record ThreadMessage(String from, String sentAt, String text, boolean mine) {}

    /**
     * A tracked promise around this email.
     *
     * @param thisThread made in this thread; otherwise an open promise between the same two people
     */
    public record PromiseNote(String id, String what, String owner, String recipient, String due, String status, String quote,
            boolean thisThread) {}

    /**
     * Everything the drafter knows about the email it answers.
     *
     * @param owner whose mailbox this is: the reply is written as them
     * @param replyTo who the reply goes to
     * @param now when the draft is written, ISO 8601, so relative dates come out right
     */
    public record DraftContext(
            String tenant,
            String owner,
            String mailbox,
            String threadId,
            String channel,
            String sourceMessageId,
            String replyTo,
            String subject,
            List<ThreadMessage> thread,
            List<PromiseNote> promises,
            String instruction,
            String now) {

        public DraftContext {
            thread = thread == null ? List.of() : List.copyOf(thread);
            promises = promises == null ? List.of() : List.copyOf(promises);
        }
    }

    /** What {@link DraftActivities#prepareDraft} found: the context to draft from, or why there is nothing to draft. */
    public record Prepared(DraftContext context, String skipped) {

        public static Prepared skip(String why) {
            return new Prepared(null, why);
        }
    }

    /** @param by which writer produced it, e.g. the Claude model name, or "template" */
    public record Written(String body, String by) {}
}
