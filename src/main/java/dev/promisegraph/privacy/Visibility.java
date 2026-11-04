package dev.promisegraph.privacy;

import dev.promisegraph.domain.Person;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.links.Link;
import dev.promisegraph.recovery.Move;
import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.recovery.RecoveryPlan;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Who may see which promise. A person sees a promise if they own it or it was made to them; if
 * they lead the owner's team; if one of their own promises is linked to it; if they approve a
 * negotiation about it; or if they handed it over while away. Nobody else does, teammates
 * included, so someone covering for a colleague sees only the promises handed to them. Quotes
 * from messages are shown only to the owner and the person the promise was made to.
 */
public final class Visibility {

    private final Directory directory;
    private final Set<String> admins;
    private final Supplier<Optional<String>> mailboxOwner;

    /** @param admins people who may use the operations pages besides team leads (e.g. the owner of a personal setup) */
    public Visibility(Directory directory, Collection<String> admins) {
        this(directory, admins, Optional::empty);
    }

    /**
     * @param mailboxOwner in a personal Gmail setup, the connected account: it sees everything in
     *     the app, since everything came from its own mailbox
     */
    public Visibility(Directory directory, Collection<String> admins, Supplier<Optional<String>> mailboxOwner) {
        this.directory = directory;
        this.admins = Set.copyOf(admins);
        this.mailboxOwner = mailboxOwner;
    }

    /** The connected Gmail account, in a personal setup. */
    public boolean ownsMailbox(String viewer) {
        return viewer != null && mailboxOwner.get().map(o -> o.equalsIgnoreCase(viewer)).orElse(false);
    }

    public boolean isLeadOf(String viewer, String person) {
        return directory.leadOf(person).map(l -> l.equals(viewer)).orElse(false);
    }

    /** Leads and admins may see operations pages and the audit log. */
    public boolean canOperate(String viewer) {
        return ownsMailbox(viewer) || admins.contains(viewer) || directory.person(viewer).map(Person::lead).orElse(false);
    }

    /** The promise IDs one viewer may see, computed once per request. */
    public Set<String> visibleIds(String viewer, Collection<Promise> promises, List<Link> links, List<NegotiationRecord> negotiations,
            List<HandoffRecord> handoffs) {
        Set<String> out = new HashSet<>();
        if (ownsMailbox(viewer)) {
            promises.forEach(p -> out.add(p.id()));
            return out;
        }
        Set<String> own = new HashSet<>();
        for (Promise p : promises) {
            if (viewer.equals(p.owner()) || viewer.equals(p.recipient()) || isLeadOf(viewer, p.owner())) {
                out.add(p.id());
            }
            if (viewer.equals(p.owner())) {
                own.add(p.id());
            }
        }
        for (Link l : links) {
            if (l.status() == Link.Status.REJECTED) {
                continue;
            }
            if (own.contains(l.from())) {
                out.add(l.to());
            }
            if (own.contains(l.to())) {
                out.add(l.from());
            }
        }
        for (NegotiationRecord n : negotiations) {
            if (n.approvers().contains(viewer) || viewer.equals(n.sourceOwner())) {
                out.add(n.sourcePromiseId());
                for (RecoveryPlan plan : n.plans()) {
                    plan.moves().stream().map(Move::promiseId).forEach(out::add);
                }
            }
        }
        for (HandoffRecord h : handoffs) {
            if (viewer.equals(h.person())) {
                h.items().forEach(i -> out.add(i.promiseId()));
            }
        }
        return out;
    }

    public boolean canSeeQuotes(String viewer, Promise p) {
        return ownsMailbox(viewer) || viewer.equals(p.owner()) || viewer.equals(p.recipient());
    }

    /** A negotiation is visible to its approvers, the late promise's owner, and everyone whose promise a plan changes. */
    public boolean canSee(String viewer, NegotiationRecord n) {
        if (ownsMailbox(viewer) || n.approvers().contains(viewer) || viewer.equals(n.sourceOwner())) {
            return true;
        }
        return n.plans().stream().flatMap(p -> p.moves().stream())
                .anyMatch(m -> viewer.equals(m.owner()) || viewer.equals(m.person()));
    }

    /**
     * The part of a handoff a viewer may see: all of it for the person away and their lead; for
     * anyone else, only the items they were asked to cover; nothing otherwise.
     */
    public Optional<HandoffRecord> view(String viewer, HandoffRecord h) {
        if (ownsMailbox(viewer) || viewer.equals(h.person()) || isLeadOf(viewer, h.person())) {
            return Optional.of(h);
        }
        List<HandoffRecord.ItemState> mine = h.items().stream()
                .filter(i -> viewer.equals(i.proposedOwner()) || viewer.equals(i.acceptedOwner())).toList();
        if (mine.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new HandoffRecord(h.tenant(), h.id(), h.person(), h.startsOn(), h.endsOn(), h.reason(), h.status(),
                h.planner(), mine, h.updatedAt()));
    }
}
