package dev.promisegraph.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.inbox.InboxItem;
import dev.promisegraph.links.Link;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Postgres link, inbox and handoff stores. Schema: migrations/002_phase2.sql. Every write is safe to repeat. */
public final class JdbcStores {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JdbcStores() {}

    private static String iso(OffsetDateTime t) {
        return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).toString();
    }

    private static OffsetDateTime ts(String iso) {
        return iso == null ? null : OffsetDateTime.parse(iso);
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not serialisable", e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable JSON column", e);
        }
    }

    public static final class Links implements LinkStore {
        private final NamedParameterJdbcTemplate jdbc;

        public Links(NamedParameterJdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        private static final String COLS = "tenant, from_promise, to_promise, status, reason, suggested_by, decided_by, created_at";

        private static Link row(ResultSet rs, int i) throws SQLException {
            return new Link(rs.getString("tenant"), rs.getString("from_promise"), rs.getString("to_promise"),
                    Link.Status.fromDb(rs.getString("status")), rs.getString("reason"), rs.getString("suggested_by"),
                    rs.getString("decided_by"), iso(rs.getObject("created_at", OffsetDateTime.class)));
        }

        @Override
        public boolean addLink(Link l) {
            return jdbc.update("INSERT INTO links (tenant, from_promise, to_promise, kind, status, reason, suggested_by, decided_by, created_at) "
                            + "VALUES (:t, :f, :to, 'blocks', :s, :r, :sb, :db, :c) ON CONFLICT DO NOTHING",
                    new MapSqlParameterSource("t", l.tenant()).addValue("f", l.from()).addValue("to", l.to())
                            .addValue("s", l.status().dbValue()).addValue("r", l.reason()).addValue("sb", l.suggestedBy())
                            .addValue("db", l.decidedBy()).addValue("c", ts(l.createdAt()))) == 1;
        }

        @Override
        public Optional<Link> getLink(String tenant, String from, String to) {
            return jdbc.query("SELECT " + COLS + " FROM links WHERE tenant = :t AND from_promise = :f AND to_promise = :to",
                    new MapSqlParameterSource("t", tenant).addValue("f", from).addValue("to", to), Links::row).stream().findFirst();
        }

        @Override
        public boolean decide(String tenant, String from, String to, Link.Status status, String by) {
            return jdbc.update("UPDATE links SET status = :s, decided_by = :by WHERE tenant = :t AND from_promise = :f AND to_promise = :to",
                    new MapSqlParameterSource("t", tenant).addValue("f", from).addValue("to", to)
                            .addValue("s", status.dbValue()).addValue("by", by)) == 1;
        }

        @Override
        public List<Link> links(String tenant) {
            return jdbc.query("SELECT " + COLS + " FROM links WHERE tenant = :t ORDER BY created_at",
                    new MapSqlParameterSource("t", tenant), Links::row);
        }

        @Override
        public List<Link> linksOf(String tenant, Collection<String> ids) {
            if (ids.isEmpty()) {
                return List.of();
            }
            return jdbc.query("SELECT " + COLS + " FROM links WHERE tenant = :t AND (from_promise IN (:ids) OR to_promise IN (:ids))",
                    new MapSqlParameterSource("t", tenant).addValue("ids", ids), Links::row);
        }

        @Override
        public List<Link> confirmedFrom(String tenant, Collection<String> ids) {
            if (ids.isEmpty()) {
                return List.of();
            }
            return jdbc.query("SELECT " + COLS + " FROM links WHERE tenant = :t AND status = 'confirmed' AND from_promise IN (:ids)",
                    new MapSqlParameterSource("t", tenant).addValue("ids", ids), Links::row);
        }
    }

    public static final class Inbox implements InboxStore {
        private final NamedParameterJdbcTemplate jdbc;

        public Inbox(NamedParameterJdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        private static final String COLS = "tenant, key, recipient, text, actions::text AS actions, promise_id, subject, created_at, status, resolved_action";

        private static InboxItem row(ResultSet rs, int i) throws SQLException {
            return new InboxItem(rs.getString("tenant"), rs.getString("key"), rs.getString("recipient"), rs.getString("text"),
                    read(rs.getString("actions"), new TypeReference<List<String>>() {}), rs.getString("promise_id"),
                    rs.getString("subject"), iso(rs.getObject("created_at", OffsetDateTime.class)), rs.getString("status"),
                    rs.getString("resolved_action"));
        }

        @Override
        public void addItem(InboxItem i) {
            jdbc.update("INSERT INTO inbox (tenant, key, recipient, text, actions, promise_id, subject, created_at, status, resolved_action) "
                            + "VALUES (:t, :k, :r, :x, CAST(:a AS jsonb), :p, :s, :c, :st, :ra) ON CONFLICT DO NOTHING",
                    new MapSqlParameterSource("t", i.tenant()).addValue("k", i.key()).addValue("r", i.recipient())
                            .addValue("x", i.text()).addValue("a", json(i.actions())).addValue("p", i.promiseId())
                            .addValue("s", i.subject()).addValue("c", ts(i.createdAt())).addValue("st", i.status())
                            .addValue("ra", i.resolvedAction()));
        }

        @Override
        public Optional<InboxItem> getItem(String tenant, String key) {
            return jdbc.query("SELECT " + COLS + " FROM inbox WHERE tenant = :t AND key = :k",
                    new MapSqlParameterSource("t", tenant).addValue("k", key), Inbox::row).stream().findFirst();
        }

        @Override
        public List<InboxItem> inbox(String tenant, String recipient, boolean openOnly) {
            return jdbc.query("SELECT " + COLS + " FROM inbox WHERE tenant = :t AND recipient = :r"
                            + (openOnly ? " AND status = 'open'" : "") + " ORDER BY created_at DESC",
                    new MapSqlParameterSource("t", tenant).addValue("r", recipient), Inbox::row);
        }

        @Override
        public boolean resolve(String tenant, String key, String action) {
            return jdbc.update("UPDATE inbox SET status = 'done', resolved_action = :a WHERE tenant = :t AND key = :k AND status = 'open'",
                    new MapSqlParameterSource("t", tenant).addValue("k", key).addValue("a", action)) == 1;
        }

        @Override
        public List<String> recipients(String tenant) {
            return jdbc.queryForList("SELECT DISTINCT recipient FROM inbox WHERE tenant = :t ORDER BY recipient",
                    new MapSqlParameterSource("t", tenant), String.class);
        }
    }

    public static final class Handoffs implements HandoffStore {
        private final NamedParameterJdbcTemplate jdbc;

        public Handoffs(NamedParameterJdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        private static String date(Date d) {
            return d == null ? null : d.toLocalDate().toString();
        }

        private static Date sqlDate(String iso) {
            return iso == null ? null : Date.valueOf(LocalDate.parse(iso));
        }

        @Override
        public boolean addAbsence(Absence a) {
            return jdbc.update("INSERT INTO absences (tenant, person, starts_on, ends_on, reason, source) "
                            + "VALUES (:t, :p, :s, :e, :r, :src) ON CONFLICT DO NOTHING",
                    new MapSqlParameterSource("t", a.tenant()).addValue("p", a.person()).addValue("s", sqlDate(a.startsOn()))
                            .addValue("e", sqlDate(a.endsOn())).addValue("r", a.reason()).addValue("src", a.source())) == 1;
        }

        @Override
        public List<Absence> absences(String tenant) {
            return jdbc.query("SELECT tenant, person, starts_on, ends_on, reason, source FROM absences WHERE tenant = :t ORDER BY starts_on",
                    new MapSqlParameterSource("t", tenant),
                    (rs, i) -> new Absence(rs.getString("tenant"), rs.getString("person"), date(rs.getDate("starts_on")),
                            date(rs.getDate("ends_on")), rs.getString("reason"), rs.getString("source")));
        }

        @Override
        public void saveHandoff(HandoffRecord h) {
            jdbc.update("INSERT INTO handoffs (tenant, id, person_id, reason, starts_on, ends_on, status, planner, items, updated_at) "
                            + "VALUES (:t, :id, :p, :r, :s, :e, :st, :pl, CAST(:items AS jsonb), :u) "
                            + "ON CONFLICT (tenant, id) DO UPDATE SET status = EXCLUDED.status, planner = EXCLUDED.planner, "
                            + "items = EXCLUDED.items, updated_at = EXCLUDED.updated_at, ends_on = EXCLUDED.ends_on",
                    new MapSqlParameterSource("t", h.tenant()).addValue("id", h.id()).addValue("p", h.person())
                            .addValue("r", h.reason()).addValue("s", sqlDate(h.startsOn())).addValue("e", sqlDate(h.endsOn()))
                            .addValue("st", h.status()).addValue("pl", h.planner()).addValue("items", json(h.items()))
                            .addValue("u", ts(h.updatedAt())));
        }

        private static HandoffRecord row(ResultSet rs, int i) throws SQLException {
            return new HandoffRecord(rs.getString("tenant"), rs.getString("id"), rs.getString("person_id"),
                    date(rs.getDate("starts_on")), date(rs.getDate("ends_on")), rs.getString("reason"), rs.getString("status"),
                    rs.getString("planner"), read(rs.getString("items"), new TypeReference<List<HandoffRecord.ItemState>>() {}),
                    iso(rs.getObject("updated_at", OffsetDateTime.class)));
        }

        private static final String COLS = "tenant, id, person_id, starts_on, ends_on, reason, status, planner, items::text AS items, updated_at";

        @Override
        public Optional<HandoffRecord> getHandoff(String tenant, String id) {
            return jdbc.query("SELECT " + COLS + " FROM handoffs WHERE tenant = :t AND id = :id",
                    new MapSqlParameterSource("t", tenant).addValue("id", id), Handoffs::row).stream().findFirst();
        }

        @Override
        public List<HandoffRecord> handoffs(String tenant) {
            return jdbc.query("SELECT " + COLS + " FROM handoffs WHERE tenant = :t ORDER BY starts_on DESC",
                    new MapSqlParameterSource("t", tenant), Handoffs::row);
        }
    }
}
