package dev.promisegraph.drafts;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Drafts in memory, for tests and single-process development. */
public final class InMemoryDraftStore implements DraftStore {

    private final Map<String, ReplyDraft> drafts = new LinkedHashMap<>();

    private static String k(String tenant, String id) {
        return tenant + "\u001f" + id;
    }

    @Override
    public synchronized boolean save(ReplyDraft draft, boolean replace) {
        String key = k(draft.tenant(), draft.id());
        if (!replace && drafts.containsKey(key)) {
            return false;
        }
        ReplyDraft before = drafts.get(key);
        drafts.put(key, before == null ? draft : withCreatedAt(draft, before.createdAt()));
        return true;
    }

    private static ReplyDraft withCreatedAt(ReplyDraft d, String createdAt) {
        return new ReplyDraft(d.id(), d.tenant(), d.owner(), d.mailbox(), d.threadId(), d.channel(), d.sourceMessageId(), d.to(),
                d.subject(), d.body(), d.status(), d.promiseIds(), d.writtenBy(), createdAt, d.updatedAt());
    }

    @Override
    public synchronized void update(ReplyDraft draft) {
        drafts.put(k(draft.tenant(), draft.id()), draft);
    }

    @Override
    public synchronized Optional<ReplyDraft> get(String tenant, String id) {
        return Optional.ofNullable(drafts.get(k(tenant, id)));
    }

    @Override
    public synchronized List<ReplyDraft> forOwner(String tenant, String owner) {
        return drafts.values().stream()
                .filter(d -> d.tenant().equals(tenant) && d.owner().equalsIgnoreCase(owner))
                .sorted(Comparator.comparing(ReplyDraft::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
    }
}
