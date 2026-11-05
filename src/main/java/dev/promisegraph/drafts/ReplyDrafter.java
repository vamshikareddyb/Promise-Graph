package dev.promisegraph.drafts;

import dev.promisegraph.drafts.DraftContracts.DraftContext;
import dev.promisegraph.drafts.DraftContracts.Written;

/** Writes the body of a reply from its context. */
public interface ReplyDrafter {

    Written write(DraftContext context);
}
