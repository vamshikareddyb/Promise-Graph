package dev.promisegraph.temporal;

import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.temporal.Contracts.HandBackRequest;
import dev.promisegraph.temporal.Contracts.TransferRequest;
import io.temporal.activity.ActivityInterface;
import java.util.List;

@ActivityInterface
public interface HandoffActivities {

    /** Records each absence and starts its handoff workflow; repeats are ignored. */
    int scheduleHandoffs(List<Absence> absences);

    /** Saves the snapshot the web app shows. */
    void saveHandoff(HandoffRecord handoff);

    /**
     * Changes the owner of each promise (a reassign signal each). Skips promises already closed.
     *
     * @return the promise ids that were moved
     */
    List<String> transfer(TransferRequest request);

    /** Moves covered promises back and sends the returning person a summary of what changed. */
    List<String> handBack(HandBackRequest request);
}
