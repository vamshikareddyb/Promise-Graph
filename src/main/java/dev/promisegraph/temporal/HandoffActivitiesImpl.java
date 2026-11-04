package dev.promisegraph.temporal;

import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseEvent;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.handoff.HandoffScheduler;
import dev.promisegraph.notify.Notification;
import dev.promisegraph.store.HandoffStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.Contracts.HandBackRequest;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.TransferRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HandoffActivitiesImpl implements HandoffActivities {

    private static final Logger log = LoggerFactory.getLogger(HandoffActivitiesImpl.class);
    /** Events that are bookkeeping rather than news for someone coming back. */
    private static final Set<String> QUIET_EVENTS = Set.of("reassigned", "tracking_started", "activated");

    private final Repository repo;
    private final HandoffStore store;
    private final HandoffScheduler scheduler;
    private final PromiseActivities notifications;
    private final WorkflowClient client;

    public HandoffActivitiesImpl(Repository repo, HandoffStore store, HandoffScheduler scheduler,
            PromiseActivities notifications, WorkflowClient client) {
        this.repo = repo;
        this.store = store;
        this.scheduler = scheduler;
        this.notifications = notifications;
        this.client = client;
    }

    @Override
    public int scheduleHandoffs(List<Absence> absences) {
        int started = 0;
        for (Absence a : absences) {
            if (scheduler.schedule(a)) {
                started++;
            }
        }
        return started;
    }

    @Override
    public void saveHandoff(HandoffRecord handoff) {
        store.saveHandoff(handoff);
    }

    private boolean reassign(String tenant, String promiseId, String newOwner, String sourceId) {
        Promise p = repo.getPromise(tenant, promiseId).orElse(null);
        if (p == null || !p.stillOpen()) {
            return false;
        }
        try {
            client.newUntypedWorkflowStub(Ids.promiseWorkflowId(promiseId))
                    .signal(PromiseActivitiesImpl.PROMISE_SIGNAL, PromiseSignal.reassign(sourceId, "handoff", newOwner));
            return true;
        } catch (WorkflowNotFoundException e) {
            log.info("promise {} closed before the handoff could move it", promiseId);
            return false;
        }
    }

    @Override
    public List<String> transfer(TransferRequest req) {
        List<String> moved = new ArrayList<>();
        for (Map.Entry<String, String> e : req.owners().entrySet()) {
            if (reassign(req.tenant(), e.getKey(), e.getValue(), "handoff:" + req.handoffId() + ":" + req.direction() + ":" + e.getKey())) {
                moved.add(e.getKey());
            }
        }
        return moved;
    }

    @Override
    public List<String> handBack(HandBackRequest req) {
        List<String> back = new ArrayList<>();
        List<String> news = new ArrayList<>();
        Instant since = OffsetDateTime.parse(req.startedAt()).toInstant();
        for (Map.Entry<String, String> e : req.covers().entrySet()) {
            String promiseId = e.getKey();
            Promise p = repo.getPromise(req.tenant(), promiseId).orElse(null);
            if (p == null) {
                continue;
            }
            // Only take it back if the cover still owns it; someone may have reassigned it again.
            if (p.stillOpen() && e.getValue().equals(p.owner())
                    && reassign(req.tenant(), promiseId, req.person(), "handoff:" + req.handoffId() + ":back:" + promiseId)) {
                back.add(promiseId);
            }
            List<String> changes = new ArrayList<>();
            for (PromiseEvent ev : repo.events(req.tenant(), promiseId)) {
                if (!QUIET_EVENTS.contains(ev.kind()) && !OffsetDateTime.parse(ev.at()).toInstant().isBefore(since)) {
                    changes.add(ev.kind().replace('_', ' ') + " by " + ev.actor() + " on " + ev.at().substring(0, 10));
                }
            }
            news.add(p.what() + " (covered by " + e.getValue() + ", now " + p.status().dbValue().replace('_', ' ')
                    + (changes.isEmpty() ? ", no changes" : ": " + String.join(", ", changes)) + ")");
        }
        String text = news.isEmpty()
                ? "Welcome back. Nothing was handed over while you were away."
                : "Welcome back. While you were away: " + String.join("; ", news) + "."
                        + (back.isEmpty() ? "" : " " + back.size() + " promise" + (back.size() == 1 ? " is" : "s are") + " yours again.");
        notifications.sendNotification(Notification.about(req.tenant(), req.person(), text,
                Ids.notificationKey(req.tenant(), "handoff-return", req.handoffId()), List.of(), null));
        return back;
    }
}
