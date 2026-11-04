package dev.promisegraph.store;

import dev.promisegraph.ops.DeadLetter;
import java.util.List;
import java.util.Optional;

/** Work that needs a person. Adding the same ID twice keeps the first. */
public interface DeadLetterStore {

    /** @return true if new */
    boolean add(DeadLetter deadLetter);

    Optional<DeadLetter> get(String tenant, String id);

    /** Newest first. */
    List<DeadLetter> list(String tenant, boolean openOnly);

    boolean setStatus(String tenant, String id, String status);
}
