package dev.promisegraph.drafts;

import dev.promisegraph.domain.Ids;
import dev.promisegraph.drafts.DraftContracts.DraftContext;
import dev.promisegraph.drafts.DraftContracts.PromiseNote;
import dev.promisegraph.drafts.DraftContracts.Written;
import java.util.List;
import java.util.Locale;

/**
 * A reply drafted for the owner of a mailbox. Nothing is ever sent: the person reads it, edits it and
 * sends it from Gmail themselves.
 *
 * @param status {@code ready} (as written), {@code edited}, {@code done} (the person replied) or {@code dismissed}
 * @param writtenBy the Claude model that wrote it, or "template"
 */
public record ReplyDraft(
        String id,
        String tenant,
        String owner,
        String mailbox,
        String threadId,
        String channel,
        String sourceMessageId,
        String to,
        String subject,
        String body,
        String status,
        List<String> promiseIds,
        String writtenBy,
        String createdAt,
        String updatedAt) {

    public static final List<String> STATUSES = List.of("ready", "edited", "done", "dismissed");

    public ReplyDraft {
        promiseIds = promiseIds == null ? List.of() : List.copyOf(promiseIds);
        status = status == null ? "ready" : status;
    }

    /**
     * One draft per email (a reply) or per promise (a follow-up), so a retried or replayed workflow
     * never makes a second one.
     *
     * @param key {@link DraftContracts.DraftRequest#key()}
     */
    public static String idFor(String tenant, String key) {
        return "draft-" + Ids.notificationKey("draft", tenant, key);
    }

    public static String forPromise(String tenant, String promiseId) {
        return idFor(tenant, "promise-" + promiseId);
    }

    public static ReplyDraft of(DraftContext ctx, Written written, String now) {
        String subject = ctx.subject() == null || ctx.subject().isBlank() ? "(no subject)" : ctx.subject().strip();
        if (!subject.toLowerCase(Locale.ROOT).startsWith("re:")) {
            subject = "Re: " + subject;
        }
        List<String> promiseIds = ctx.focusPromiseId() != null ? List.of(ctx.focusPromiseId())
                : ctx.promises().stream().filter(PromiseNote::thisThread).map(PromiseNote::id).toList();
        return new ReplyDraft(idFor(ctx.tenant(), ctx.key()), ctx.tenant(), ctx.owner(), ctx.mailbox(), ctx.threadId(),
                ctx.channel(), ctx.sourceMessageId(), ctx.replyTo(), subject, written.body(), "ready", promiseIds, written.by(), now, now);
    }

    public ReplyDraft withBody(String b, String at) {
        return new ReplyDraft(id, tenant, owner, mailbox, threadId, channel, sourceMessageId, to, subject, b, "edited", promiseIds,
                writtenBy, createdAt, at);
    }

    public ReplyDraft withStatus(String s, String at) {
        return new ReplyDraft(id, tenant, owner, mailbox, threadId, channel, sourceMessageId, to, subject, body, s, promiseIds,
                writtenBy, createdAt, at);
    }

    /** Still waiting for the person: shown at the top of the Drafts page. */
    public boolean open() {
        return "ready".equals(status) || "edited".equals(status);
    }
}
