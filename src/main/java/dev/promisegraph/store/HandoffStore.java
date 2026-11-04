package dev.promisegraph.store;

import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.HandoffRecord;
import java.util.List;
import java.util.Optional;

/** Planned absences and the handoffs that cover them. */
public interface HandoffStore {

    /** Insert if absent (one per person and start date). True if new. */
    boolean addAbsence(Absence absence);

    List<Absence> absences(String tenant);

    /** Replaces the snapshot. */
    void saveHandoff(HandoffRecord handoff);

    Optional<HandoffRecord> getHandoff(String tenant, String id);

    /** Newest start first. */
    List<HandoffRecord> handoffs(String tenant);
}
