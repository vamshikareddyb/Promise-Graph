package dev.promisegraph.drafts;

import java.util.List;
import java.util.Optional;

/** Reply drafts. Writes are safe to repeat, so a retried activity never duplicates a draft. */
public interface DraftStore {

    /** Stores a newly written draft. Unless {@code replace}, an existing draft (perhaps already edited) is kept. True if written. */
    boolean save(ReplyDraft draft, boolean replace);

    /** Saves the person's edit or a status change. */
    void update(ReplyDraft draft);

    Optional<ReplyDraft> get(String tenant, String id);

    /** The owner's drafts, newest first. */
    List<ReplyDraft> forOwner(String tenant, String owner);
}
