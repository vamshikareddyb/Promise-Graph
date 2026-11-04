package dev.promisegraph.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promisegraph.domain.Evidence;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseEvent;
import dev.promisegraph.domain.PromiseStatus;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Postgres repository. Schema: migrations/001_init.sql. Every statement is safe to repeat:
 * inserts use ON CONFLICT DO NOTHING and snapshots only replace older versions.
 */
public final class JdbcRepository implements Repository {

    private static final String COLUMNS =
            "id, tenant, owner_id, recipient, what, due, status, confidence, channel, created_at, updated_at, version";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcRepository(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static String iso(OffsetDateTime t) {
        return t.withOffsetSameInstant(ZoneOffset.UTC).toString();
    }

    private static Promise row(ResultSet rs) throws SQLException {
        Date due = rs.getDate("due");
        return new Promise(
                rs.getString("id"), rs.getString("tenant"), rs.getString("owner_id"), rs.getString("what"),
                PromiseStatus.fromDb(rs.getString("status")), rs.getDouble("confidence"),
                iso(rs.getObject("created_at", OffsetDateTime.class)), iso(rs.getObject("updated_at", OffsetDateTime.class)),
                rs.getString("recipient"), due == null ? null : due.toLocalDate().toString(),
                rs.getString("channel"), List.of(), rs.getInt("version"));
    }

    private List<Promise> withEvidence(String tenant, List<Promise> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, List<Evidence>> evidence = new HashMap<>();
        jdbc.query("SELECT promise_id, message_id, quote, channel FROM evidence WHERE tenant = :t AND promise_id IN (:ids)",
                new MapSqlParameterSource("t", tenant).addValue("ids", rows.stream().map(Promise::id).toList()),
                rs -> {
                    evidence.computeIfAbsent(rs.getString("promise_id"), k -> new ArrayList<>())
                            .add(new Evidence(rs.getString("message_id"), rs.getString("quote"), rs.getString("channel")));
                });
        return rows.stream()
                .map(p -> new Promise(p.id(), p.tenant(), p.owner(), p.what(), p.status(), p.confidence(),
                        p.createdAt(), p.updatedAt(), p.recipient(), p.due(), p.channel(),
                        evidence.getOrDefault(p.id(), List.of()), p.version()))
                .toList();
    }

    @Override
    public boolean claimMessage(String tenant, String messageId, String claimant) {
        var params = new MapSqlParameterSource("t", tenant).addValue("m", messageId).addValue("c", claimant);
        jdbc.update("INSERT INTO seen_messages (tenant, message_id, claimed_by) VALUES (:t, :m, :c) ON CONFLICT DO NOTHING", params);
        String holder = jdbc.queryForObject(
                "SELECT claimed_by FROM seen_messages WHERE tenant = :t AND message_id = :m", params, String.class);
        return claimant.equals(holder);
    }

    @Override
    public Optional<Promise> getPromise(String tenant, String promiseId) {
        List<Promise> rows = jdbc.query("SELECT " + COLUMNS + " FROM promises WHERE tenant = :t AND id = :id",
                new MapSqlParameterSource("t", tenant).addValue("id", promiseId), (rs, i) -> row(rs));
        return withEvidence(tenant, rows).stream().findFirst();
    }

    @Override
    public Set<String> existingIds(String tenant, Collection<String> promiseIds) {
        if (promiseIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("SELECT id FROM promises WHERE tenant = :t AND id IN (:ids)",
                new MapSqlParameterSource("t", tenant).addValue("ids", promiseIds), String.class));
    }

    @Override
    public List<Promise> openPromises(String tenant, Collection<String> owners) {
        if (owners.isEmpty()) {
            return List.of();
        }
        List<String> open = List.of("detected", "active", "at_risk");
        return withEvidence(tenant, jdbc.query(
                "SELECT " + COLUMNS + " FROM promises WHERE tenant = :t AND owner_id IN (:owners) AND status IN (:s)",
                new MapSqlParameterSource("t", tenant).addValue("owners", owners).addValue("s", open), (rs, i) -> row(rs)));
    }

    @Override
    public List<Promise> promisesByStatus(String tenant, Collection<PromiseStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        return withEvidence(tenant, jdbc.query("SELECT " + COLUMNS + " FROM promises WHERE tenant = :t AND status IN (:s)",
                new MapSqlParameterSource("t", tenant).addValue("s", statuses.stream().map(PromiseStatus::dbValue).toList()),
                (rs, i) -> row(rs)));
    }

    private MapSqlParameterSource params(Promise p) {
        return new MapSqlParameterSource("t", p.tenant())
                .addValue("id", p.id()).addValue("owner", p.owner()).addValue("recipient", p.recipient())
                .addValue("what", p.what()).addValue("due", p.due() == null ? null : Date.valueOf(LocalDate.parse(p.due())))
                .addValue("status", p.status().dbValue()).addValue("confidence", p.confidence())
                .addValue("channel", p.channel())
                .addValue("created", OffsetDateTime.parse(p.createdAt())).addValue("updated", OffsetDateTime.parse(p.updatedAt()))
                .addValue("version", p.version());
    }

    private void insertEvidence(Promise p) {
        for (Evidence e : p.evidence()) {
            jdbc.update("INSERT INTO evidence (tenant, promise_id, message_id, channel, quote) "
                            + "VALUES (:t, :p, :m, :c, :q) ON CONFLICT DO NOTHING",
                    new MapSqlParameterSource("t", p.tenant()).addValue("p", p.id()).addValue("m", e.messageId())
                            .addValue("c", e.channel()).addValue("q", e.quote()));
        }
    }

    @Override
    public void createPromise(Promise p) {
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO promises (tenant, id, owner_id, recipient, what, due, status, confidence, channel, "
                    + "created_at, updated_at, version) VALUES (:t, :id, :owner, :recipient, :what, :due, :status, "
                    + ":confidence, :channel, :created, :updated, :version) ON CONFLICT DO NOTHING", params(p));
            insertEvidence(p);
        });
    }

    @Override
    public boolean savePromise(Promise p) {
        Boolean saved = tx.execute(s -> {
            int rows = jdbc.update("UPDATE promises SET owner_id = :owner, recipient = :recipient, what = :what, due = :due, status = :status, "
                    + "confidence = :confidence, updated_at = :updated, version = :version "
                    + "WHERE tenant = :t AND id = :id AND version < :version", params(p));
            if (rows == 0) {
                return false;
            }
            insertEvidence(p);
            return true;
        });
        return Boolean.TRUE.equals(saved);
    }

    @Override
    public boolean appendEvent(PromiseEvent e) {
        String detail;
        try {
            detail = MAPPER.writeValueAsString(e.detail());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("event detail is not serialisable", ex);
        }
        return jdbc.update("INSERT INTO events (tenant, promise_id, kind, actor, at, detail, idem_key) "
                        + "VALUES (:t, :p, :k, :a, :at, CAST(:d AS jsonb), :key) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("t", e.tenant()).addValue("p", e.promiseId()).addValue("k", e.kind())
                        .addValue("a", e.actor()).addValue("at", OffsetDateTime.parse(e.at()))
                        .addValue("d", detail).addValue("key", e.idemKey())) == 1;
    }

    @Override
    public List<PromiseEvent> events(String tenant, String promiseId) {
        return jdbc.query("SELECT tenant, promise_id, kind, actor, at, detail::text AS detail, idem_key FROM events "
                        + "WHERE tenant = :t AND promise_id = :p ORDER BY id",
                new MapSqlParameterSource("t", tenant).addValue("p", promiseId),
                (rs, i) -> {
                    Map<String, String> detail;
                    try {
                        detail = MAPPER.readValue(rs.getString("detail"), new TypeReference<Map<String, String>>() {});
                    } catch (JsonProcessingException ex) {
                        detail = Map.of();
                    }
                    return new PromiseEvent(rs.getString("tenant"), rs.getString("promise_id"), rs.getString("kind"),
                            rs.getString("actor"), iso(rs.getObject("at", OffsetDateTime.class)),
                            rs.getString("idem_key"), detail);
                });
    }

    @Override
    public boolean notificationSent(String tenant, String key) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM sent_notifications WHERE tenant = :t AND idem_key = :k",
                new MapSqlParameterSource("t", tenant).addValue("k", key), Integer.class);
        return n != null && n > 0;
    }

    @Override
    public void recordNotification(String tenant, String key, String recipient) {
        jdbc.update("INSERT INTO sent_notifications (tenant, idem_key, recipient) VALUES (:t, :k, :r) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("t", tenant).addValue("k", key).addValue("r", recipient));
    }
}
