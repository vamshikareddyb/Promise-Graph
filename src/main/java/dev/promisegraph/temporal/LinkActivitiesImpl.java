package dev.promisegraph.temporal;

import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.links.Link;
import dev.promisegraph.links.Ripple;
import dev.promisegraph.notify.Notification;
import dev.promisegraph.store.LinkStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LinkActivitiesImpl implements LinkActivities {

    private static final Logger log = LoggerFactory.getLogger(LinkActivitiesImpl.class);

    private final Repository repo;
    private final LinkStore links;
    private final PromiseActivities notifications;
    private final WorkflowClient client;

    /** @param notifications used for its idempotent {@code sendNotification} */
    public LinkActivitiesImpl(Repository repo, LinkStore links, PromiseActivities notifications, WorkflowClient client) {
        this.repo = repo;
        this.links = links;
        this.notifications = notifications;
        this.client = client;
    }

    @Override
    public Ripple.Result computeRipple(RippleRequest req) {
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Promise> promises = new HashMap<>();
        Set<String> frontier = new LinkedHashSet<>(List.of(req.promiseId()));
        Set<String> seen = new LinkedHashSet<>(frontier);
        // Load the confirmed graph level by level; one extra level so loops back to the source are seen.
        for (int depth = 0; depth <= Ripple.DEFAULT_MAX_DEPTH && !frontier.isEmpty(); depth++) {
            Set<String> next = new LinkedHashSet<>();
            for (Link l : links.confirmedFrom(req.tenant(), frontier)) {
                dependents.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l.to());
                if (seen.add(l.to())) {
                    next.add(l.to());
                }
            }
            frontier = next;
        }
        for (String id : seen) {
            repo.getPromise(req.tenant(), id).ifPresent(p -> promises.put(id, p));
        }
        return Ripple.compute(req.promiseId(), req.oldDue(), req.newDue(), dependents, promises, Ripple.DEFAULT_MAX_DEPTH);
    }

    private static String describe(Promise p) {
        return p.owner() + "'s promise to " + p.what();
    }

    @Override
    public int applyRipple(RippleRequest req, Ripple.Result result) {
        Promise source = repo.getPromise(req.tenant(), req.promiseId()).orElse(null);
        if (source == null) {
            return 0;
        }
        String change = "missed".equals(req.cause())
                ? describe(source) + " was missed"
                : describe(source) + (req.newDue() == null ? " is running late" : " is now expected " + req.newDue());
        String changeKey = req.promiseId() + ":" + req.cause() + ":" + req.newDue();

        int told = 0;
        List<String> lines = new ArrayList<>();
        for (Ripple.Impact impact : result.impacted()) {
            String text = change;
            if (!impact.via().equals(req.promiseId())) {
                text += ", which delays " + repo.getPromise(req.tenant(), impact.via()).map(LinkActivitiesImpl::describe).orElse("another promise");
            }
            PromiseSignal signal = new PromiseSignal("upstream_risk", "ripple:" + changeKey, source.owner(), impact.likelyDue(),
                    text, null, "", null);
            try {
                client.newUntypedWorkflowStub(Ids.promiseWorkflowId(impact.promiseId())).signal(PromiseActivitiesImpl.PROMISE_SIGNAL, signal);
                told++;
                lines.add(impact.what() + " (" + impact.owner() + ", due " + impact.due()
                        + (impact.likelyDue() == null ? "" : ", now likely " + impact.likelyDue()) + ")");
            } catch (WorkflowNotFoundException e) {
                log.info("promise {} already closed; not told about the ripple", impact.promiseId());
            }
        }

        for (List<String> cycle : result.cycles()) {
            List<String> names = new ArrayList<>();
            Set<String> owners = new LinkedHashSet<>();
            for (String id : cycle) {
                repo.getPromise(req.tenant(), id).ifPresent(p -> {
                    names.add(p.what() + " (" + p.owner() + ")");
                    owners.add(p.owner());
                });
            }
            String text = "These promises wait on each other, so none can finish first: " + String.join(" → ", names)
                    + ". One of them has to change.";
            for (String owner : owners) {
                notifications.sendNotification(Notification.about(req.tenant(), owner, text,
                        Ids.notificationKey(req.tenant(), "cycle", String.join(">", cycle), owner), List.of(), null));
            }
        }

        if (!lines.isEmpty()) {
            String text = "Your change to \"" + source.what() + "\" affects " + lines.size() + " promise"
                    + (lines.size() == 1 ? "" : "s") + " that depend on it: " + String.join("; ", lines)
                    + ". Their owners have been told.";
            notifications.sendNotification(Notification.about(req.tenant(), source.owner(), text,
                    Ids.notificationKey(req.tenant(), "ripple-summary", changeKey), List.of(), source.id()));
        }
        return told;
    }
}
