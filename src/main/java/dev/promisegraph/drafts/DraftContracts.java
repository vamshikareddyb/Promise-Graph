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
     * Draft an email: a reply to one email, or (with {@code promiseId}) a follow-up about one promise
     * in the thread it was made in.
     *
     * @param message the email, as extraction saw it (already redacted); for a follow-up, the email the promise came from
     * @param instruction what the person asked for ("shorter", "decline politely"), or null
     * @param replace true when regenerating: overwrite the existing draft
     * @param promiseId the promise to follow up on, or null for a plain reply
     */
    public record DraftRequest(String tenant, Message message, String instruction, boolean replace, String promiseId) {

        public static DraftRequest first(String tenant, Message message) {
            return new DraftRequest(tenant, message, null, false, null);
        }

        public static DraftRequest followUp(String tenant, Message message, String promiseId, String instruction, boolean replace) {
            return new DraftRequest(tenant, message, instruction, replace, promiseId);
        }

        /** What the draft is keyed by: the promise for a follow-up, else the email. */
        public String key() {
            return promiseId == null ? message.id() : "promise-" + promiseId;
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
     * @param focusPromiseId for a follow-up, the promise it is about; null for a plain reply
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
            String now,
            String focusPromiseId) {

        public DraftContext {
            thread = thread == null ? List.of() : List.copyOf(thread);
            promises = promises == null ? List.of() : List.copyOf(promises);
        }

        /** The promise a follow-up is about, if it is among the promises. */
        public java.util.Optional<PromiseNote> focus() {
            return focusPromiseId == null ? java.util.Optional.empty()
                    : promises.stream().filter(p -> p.id().equals(focusPromiseId)).findFirst();
        }

        /** The draft's key: the same one the request had. */
        public String key() {
            return focusPromiseId == null ? sourceMessageId : "promise-" + focusPromiseId;
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
