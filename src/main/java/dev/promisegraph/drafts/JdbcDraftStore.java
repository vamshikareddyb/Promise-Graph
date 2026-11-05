package dev.promisegraph.drafts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Postgres drafts. Schema: migrations/006_drafts.sql. */
public final class JdbcDraftStore implements DraftStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLS = "id, tenant, owner, mailbox, thread_id, channel, source_message_id, to_address, subject, body, "
            + "status, promise_ids::text AS promise_ids, written_by, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDraftStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String iso(OffsetDateTime t) {
        return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).toString();
    }

    private static OffsetDateTime ts(String iso) {
        return iso == null ? null : OffsetDateTime.parse(iso);
    }

    private static ReplyDraft row(ResultSet rs, int i) throws SQLException {
        List<String> ids;
        try {
            ids = MAPPER.readValue(rs.getString("promise_ids"), new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable promise_ids", e);
        }
        return new ReplyDraft(rs.getString("id"), rs.getString("tenant"), rs.getString("owner"), rs.getString("mailbox"),
                rs.getString("thread_id"), rs.getString("channel"), rs.getString("source_message_id"), rs.getString("to_address"),
                rs.getString("subject"), rs.getString("body"), rs.getString("status"), ids, rs.getString("written_by"),
                iso(rs.getObject("created_at", OffsetDateTime.class)), iso(rs.getObject("updated_at", OffsetDateTime.class)));
    }

    private static MapSqlParameterSource params(ReplyDraft d) {
        String ids;
        try {
            ids = MAPPER.writeValueAsString(d.promiseIds());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not serialisable", e);
        }
        return new MapSqlParameterSource("id", d.id()).addValue("t", d.tenant()).addValue("o", d.owner()).addValue("m", d.mailbox())
                .addValue("th", d.threadId()).addValue("ch", d.channel()).addValue("src", d.sourceMessageId()).addValue("to", d.to())
                .addValue("su", d.subject()).addValue("b", d.body()).addValue("st", d.status()).addValue("p", ids)
                .addValue("w", d.writtenBy()).addValue("c", ts(d.createdAt())).addValue("u", ts(d.updatedAt()));
    }

    @Override
    public boolean save(ReplyDraft d, boolean replace) {
        String insert = "INSERT INTO reply_drafts (id, tenant, owner, mailbox, thread_id, channel, source_message_id, to_address, subject, "
                + "body, status, promise_ids, written_by, created_at, updated_at) VALUES (:id, :t, :o, :m, :th, :ch, :src, :to, :su, :b, "
                + ":st, CAST(:p AS jsonb), :w, :c, :u) ";
        String conflict = replace
                ? "ON CONFLICT (tenant, id) DO UPDATE SET subject = EXCLUDED.subject, body = EXCLUDED.body, status = EXCLUDED.status, "
                        + "promise_ids = EXCLUDED.promise_ids, written_by = EXCLUDED.written_by, to_address = EXCLUDED.to_address, "
                        + "updated_at = EXCLUDED.updated_at"
                : "ON CONFLICT DO NOTHING";
        return jdbc.update(insert + conflict, params(d)) > 0;
    }

    @Override
    public void update(ReplyDraft d) {
        jdbc.update("UPDATE reply_drafts SET body = :b, status = :st, updated_at = :u WHERE tenant = :t AND id = :id", params(d));
    }

    @Override
    public Optional<ReplyDraft> get(String tenant, String id) {
        return jdbc.query("SELECT " + COLS + " FROM reply_drafts WHERE tenant = :t AND id = :id",
                new MapSqlParameterSource("t", tenant).addValue("id", id), JdbcDraftStore::row).stream().findFirst();
    }

    @Override
    public List<ReplyDraft> forOwner(String tenant, String owner) {
        return jdbc.query("SELECT " + COLS + " FROM reply_drafts WHERE tenant = :t AND lower(owner) = lower(:o) ORDER BY created_at DESC",
                new MapSqlParameterSource("t", tenant).addValue("o", owner), JdbcDraftStore::row);
    }
}
