package dev.promisegraph.team;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Schema: migrations/005_team.sql. */
public final class JdbcTeamStore implements TeamStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTeamStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Member row(ResultSet rs, int i) throws SQLException {
        return new Member(rs.getString("tenant"), rs.getString("email"), rs.getString("name"), rs.getString("team"),
                rs.getString("role"), rs.getBoolean("lead"), rs.getBoolean("admin"), rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant().toString());
    }

    private static MapSqlParameterSource params(Member m) {
        return new MapSqlParameterSource("t", m.tenant()).addValue("e", m.email()).addValue("n", m.name())
                .addValue("team", m.team()).addValue("role", m.role()).addValue("lead", m.lead()).addValue("admin", m.admin())
                .addValue("active", m.active()).addValue("c", Timestamp.from(Instant.parse(m.createdAt())));
    }

    @Override
    public List<Member> members(String tenant) {
        return jdbc.query("SELECT * FROM people WHERE tenant = :t ORDER BY name", new MapSqlParameterSource("t", tenant), JdbcTeamStore::row);
    }

    @Override
    public Optional<Member> member(String tenant, String email) {
        return jdbc.query("SELECT * FROM people WHERE tenant = :t AND email = :e",
                new MapSqlParameterSource("t", tenant).addValue("e", email.toLowerCase(Locale.ROOT)), JdbcTeamStore::row).stream().findFirst();
    }

    @Override
    public Member addIfAbsent(Member m) {
        jdbc.update("INSERT INTO people (tenant, email, name, team, role, lead, admin, active, created_at) "
                + "VALUES (:t, :e, :n, :team, :role, :lead, :admin, :active, :c) ON CONFLICT (tenant, email) DO NOTHING", params(m));
        return member(m.tenant(), m.email()).orElse(m);
    }

    @Override
    public void update(Member m) {
        jdbc.update("UPDATE people SET name = :n, team = :team, role = :role, lead = :lead, admin = :admin, active = :active "
                + "WHERE tenant = :t AND email = :e", params(m));
    }

    @Override
    public Optional<String> refreshToken(String tenant, String email) {
        return jdbc.queryForList("SELECT refresh_token FROM accounts WHERE tenant = :t AND email = :e",
                new MapSqlParameterSource("t", tenant).addValue("e", email.toLowerCase(Locale.ROOT)), String.class).stream().findFirst();
    }

    @Override
    public void saveRefreshToken(String tenant, String email, String encrypted, String signedInAt) {
        jdbc.update("INSERT INTO accounts (tenant, email, refresh_token, signed_in_at) VALUES (:t, :e, :r, :s) "
                        + "ON CONFLICT (tenant, email) DO UPDATE SET refresh_token = EXCLUDED.refresh_token, signed_in_at = EXCLUDED.signed_in_at",
                new MapSqlParameterSource("t", tenant).addValue("e", email.toLowerCase(Locale.ROOT)).addValue("r", encrypted)
                        .addValue("s", Timestamp.from(Instant.parse(signedInAt))));
    }

    @Override
    public List<String> accounts(String tenant) {
        return jdbc.queryForList("SELECT email FROM accounts WHERE tenant = :t ORDER BY email", new MapSqlParameterSource("t", tenant), String.class);
    }
}
