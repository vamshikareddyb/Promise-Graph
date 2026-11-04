package dev.promisegraph.inbox;

import dev.promisegraph.links.Link;
import dev.promisegraph.store.InboxStore;
import dev.promisegraph.store.LinkStore;
import dev.promisegraph.temporal.Contracts.HandoffResponse;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.NegotiationContracts.Vote;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Turns a button press in the inbox into a signal to the right workflow (a promise, a handoff or a
 * negotiation) or a link decision, then marks the item done. Signals carry the item and action as
 * their source, so a double click or a retried request acts once.
 */
public final class InboxService {

    /** What the person pressed, plus any input the action needs. */
    public record ActionRequest(String action, String by, String due, String what) {}

    public static class ActionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public ActionException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final InboxStore inbox;
    private final LinkStore links;
    private final Signals signals;

    public InboxService(InboxStore inbox, LinkStore links, Signals signals) {
        this.inbox = inbox;
        this.links = links;
        this.signals = signals;
    }

    public InboxItem act(String tenant, String key, ActionRequest req) {
        InboxItem item = inbox.getItem(tenant, key).orElseThrow(() -> new ActionException(404, "no such inbox item"));
        if (!item.open()) {
            throw new ActionException(409, "already done: " + item.resolvedAction());
        }
        if (req.action() == null || !item.actions().contains(req.action())) {
            throw new ActionException(400, "this item offers " + item.actions());
        }
        String by = req.by() == null || req.by().isBlank() ? item.recipient() : req.by();
        String source = "inbox:" + key + ":" + req.action();
        try {
            if (item.subject() != null && item.subject().startsWith("link:")) {
                decideLink(tenant, item.subject().substring(5), req.action(), by);
            } else if (item.subject() != null && item.subject().startsWith("negotiation:")) {
                signals.toNegotiation(negotiationId(item.subject()), vote(item.subject(), req, by, source));
            } else if (item.subject() != null && item.subject().startsWith("handoff:")) {
                String[] parts = item.subject().split(":", 3);
                signals.toHandoff(parts[1], new HandoffResponse(req.action(), parts[2], by, null, source));
            } else {
                signals.toPromise(item.promiseId(), promiseSignal(req, by, source));
            }
        } catch (Signals.ClosedException e) {
            inbox.resolve(tenant, key, "closed");
            throw new ActionException(409, e.getMessage());
        }
        inbox.resolve(tenant, key, req.action());
        return inbox.getItem(tenant, key).orElse(item);
    }

    /** Subjects look like {@code negotiation:<id>:<round>}; the id itself may contain colons. */
    static String negotiationId(String subject) {
        String rest = subject.substring("negotiation:".length());
        return rest.substring(0, rest.lastIndexOf(':'));
    }

    static Vote vote(String subject, ActionRequest req, String by, String source) {
        int round = Integer.parseInt(subject.substring(subject.lastIndexOf(':') + 1));
        String reason = req.what() == null || req.what().isBlank() ? null : req.what().strip();
        if (req.action().startsWith("approve ")) {
            return new Vote("approve", round, req.action().substring("approve ".length()).strip(), by, reason, source);
        }
        if (req.action().equals("reject all")) {
            return new Vote("reject", round, null, by, reason, source);
        }
        throw new ActionException(400, "unknown plan action " + req.action());
    }

    private void decideLink(String tenant, String fromTo, String action, String by) {
        String[] ft = fromTo.split(">", 2);
        Link.Status status = switch (action) {
            case "confirm link" -> Link.Status.CONFIRMED;
            case "not related" -> Link.Status.REJECTED;
            default -> throw new ActionException(400, "unknown link action " + action);
        };
        if (!links.decide(tenant, ft[0], ft[1], status, by)) {
            throw new ActionException(404, "that link no longer exists");
        }
    }

    private static String requireDate(String due) {
        if (due == null || due.isBlank()) {
            throw new ActionException(400, "this action needs a date (YYYY-MM-DD)");
        }
        try {
            return LocalDate.parse(due.strip()).toString();
        } catch (DateTimeParseException e) {
            throw new ActionException(400, "dates look like 2026-11-20");
        }
    }

    static PromiseSignal promiseSignal(ActionRequest req, String by, String source) {
        return switch (req.action()) {
            case "confirm", "keep date" -> PromiseSignal.of("confirm", source, by);
            case "edit" -> new PromiseSignal("confirm", source, by,
                    req.due() == null || req.due().isBlank() ? null : requireDate(req.due()),
                    req.what() == null || req.what().isBlank() ? null : req.what().strip(), null, "", null);
            case "dismiss" -> PromiseSignal.of("dismiss", source, by);
            case "kept" -> PromiseSignal.of("kept", source, by);
            case "slip", "new date" -> new PromiseSignal("slip", source, by, requireDate(req.due()), null, null, "", null);
            case "set date" -> new PromiseSignal("update", source, by, requireDate(req.due()), null, null, "", null);
            default -> throw new ActionException(400, "unknown action " + req.action());
        };
    }
}
