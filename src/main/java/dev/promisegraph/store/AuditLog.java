package dev.promisegraph.store;

import dev.promisegraph.ops.AuditEntry;
import java.util.List;

/** Who did what, append-only. */
public interface AuditLog {

    void record(AuditEntry entry);

    /** Newest first, at most {@code limit}. */
    List<AuditEntry> recent(String tenant, int limit);
}
