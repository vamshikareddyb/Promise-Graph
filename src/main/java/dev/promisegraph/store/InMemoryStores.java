package dev.promisegraph.store;

import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.inbox.InboxItem;
import dev.promisegraph.links.Link;
import dev.promisegraph.ops.AuditEntry;
import dev.promisegraph.ops.DeadLetter;
import dev.promisegraph.recovery.NegotiationRecord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** In-memory link, inbox, handoff and negotiation stores, for tests and single-process development. */
public final class InMemoryStores {

    private InMemoryStores() {}

    public static final class Links implements LinkStore {
        private final Map<String, Link> links = new LinkedHashMap<>();

        private static String k(String tenant, String from, String to) {
            return tenant + "\u001f" + from + "\u001f" + to;
        }

        @Override
        public synchronized boolean addLink(Link link) {
            return links.putIfAbsent(k(link.tenant(), link.from(), link.to()), link) == null;
        }

        @Override
        public synchronized Optional<Link> getLink(String tenant, String from, String to) {
            return Optional.ofNullable(links.get(k(tenant, from, to)));
        }

        @Override
        public synchronized boolean decide(String tenant, String from, String to, Link.Status status, String by) {
            Link l = links.get(k(tenant, from, to));
            if (l == null) {
                return false;
            }
            links.put(k(tenant, from, to), l.withDecision(status, by));
            return true;
        }

        @Override
        public synchronized List<Link> links(String tenant) {
            return links.values().stream().filter(l -> l.tenant().equals(tenant)).toList();
        }

        @Override
        public synchronized List<Link> linksOf(String tenant, Collection<String> ids) {
            return links(tenant).stream().filter(l -> ids.contains(l.from()) || ids.contains(l.to())).toList();
        }

        @Override
        public synchronized List<Link> confirmedFrom(String tenant, Collection<String> ids) {
            return links(tenant).stream().filter(l -> l.status() == Link.Status.CONFIRMED && ids.contains(l.from())).toList();
        }
    }

    public static final class Inbox implements InboxStore {
        private final Map<String, InboxItem> items = new LinkedHashMap<>();

        @Override
        public synchronized void addItem(InboxItem item) {
            items.putIfAbsent(item.tenant() + "\u001f" + item.key(), item);
        }

        @Override
        public synchronized Optional<InboxItem> getItem(String tenant, String key) {
            return Optional.ofNullable(items.get(tenant + "\u001f" + key));
        }

        @Override
        public synchronized List<InboxItem> inbox(String tenant, String recipient, boolean openOnly) {
            List<InboxItem> out = new ArrayList<>();
            for (InboxItem i : items.values()) {
                if (i.tenant().equals(tenant) && i.recipient().equals(recipient) && (!openOnly || i.open())) {
                    out.add(i);
                }
            }
            out.sort(Comparator.comparing(InboxItem::createdAt).reversed());
            return out;
        }

        @Override
        public synchronized boolean resolve(String tenant, String key, String action) {
            InboxItem i = items.get(tenant + "\u001f" + key);
            if (i == null || !i.open()) {
                return false;
            }
            items.put(tenant + "\u001f" + key, i.resolved(action));
            return true;
        }

        @Override
        public synchronized List<String> recipients(String tenant) {
            Set<String> out = new TreeSet<>();
            items.values().stream().filter(i -> i.tenant().equals(tenant)).forEach(i -> out.add(i.recipient()));
            return List.copyOf(out);
        }
    }

    public static final class DeadLetters implements DeadLetterStore {
        private final Map<String, DeadLetter> byKey = new LinkedHashMap<>();

        @Override
        public synchronized boolean add(DeadLetter d) {
            return byKey.putIfAbsent(d.tenant() + "\u001f" + d.id(), d) == null;
        }

        @Override
        public synchronized Optional<DeadLetter> get(String tenant, String id) {
            return Optional.ofNullable(byKey.get(tenant + "\u001f" + id));
        }

        @Override
        public synchronized List<DeadLetter> list(String tenant, boolean openOnly) {
            return byKey.values().stream().filter(d -> d.tenant().equals(tenant) && (!openOnly || d.status().equals("open")))
                    .sorted(Comparator.comparing(DeadLetter::createdAt).reversed()).toList();
        }

        @Override
        public synchronized boolean setStatus(String tenant, String id, String status) {
            DeadLetter d = byKey.get(tenant + "\u001f" + id);
            if (d == null) {
                return false;
            }
            byKey.put(tenant + "\u001f" + id, d.withStatus(status));
            return true;
        }
    }

    public static final class Audit implements AuditLog {
        private final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public synchronized void record(AuditEntry e) {
            entries.add(e);
        }

        @Override
        public synchronized List<AuditEntry> recent(String tenant, int limit) {
            List<AuditEntry> out = new ArrayList<>();
            for (int i = entries.size() - 1; i >= 0 && out.size() < limit; i--) {
                if (entries.get(i).tenant().equals(tenant)) {
                    out.add(entries.get(i));
                }
            }
            return out;
        }
    }

    public static final class Negotiations implements NegotiationStore {
        private final Map<String, NegotiationRecord> byKey = new LinkedHashMap<>();

        @Override
        public synchronized void save(NegotiationRecord n) {
            byKey.put(n.tenant() + "\u001f" + n.id(), n);
        }

        @Override
        public synchronized Optional<NegotiationRecord> get(String tenant, String id) {
            return Optional.ofNullable(byKey.get(tenant + "\u001f" + id));
        }

        @Override
        public synchronized List<NegotiationRecord> list(String tenant) {
            return byKey.values().stream().filter(n -> n.tenant().equals(tenant))
                    .sorted(Comparator.comparing(NegotiationRecord::createdAt).reversed()).toList();
        }

        @Override
        public synchronized List<NegotiationRecord> openFor(String tenant, String sourcePromiseId) {
            return list(tenant).stream().filter(n -> n.sourcePromiseId().equals(sourcePromiseId) && n.stillOpen()).toList();
        }
    }

    public static final class Handoffs implements HandoffStore {
        private final Map<String, Absence> absences = new LinkedHashMap<>();
        private final Map<String, HandoffRecord> handoffs = new LinkedHashMap<>();

        @Override
        public synchronized boolean addAbsence(Absence a) {
            return absences.putIfAbsent(a.tenant() + "\u001f" + a.person() + "\u001f" + a.startsOn(), a) == null;
        }

        @Override
        public synchronized List<Absence> absences(String tenant) {
            return absences.values().stream().filter(a -> a.tenant().equals(tenant)).toList();
        }

        @Override
        public synchronized void saveHandoff(HandoffRecord h) {
            handoffs.put(h.tenant() + "\u001f" + h.id(), h);
        }

        @Override
        public synchronized Optional<HandoffRecord> getHandoff(String tenant, String id) {
            return Optional.ofNullable(handoffs.get(tenant + "\u001f" + id));
        }

        @Override
        public synchronized List<HandoffRecord> handoffs(String tenant) {
            return handoffs.values().stream().filter(h -> h.tenant().equals(tenant))
                    .sorted(Comparator.comparing(HandoffRecord::startsOn).reversed()).toList();
        }
    }
}
