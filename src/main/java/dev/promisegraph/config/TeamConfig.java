package dev.promisegraph.config;

import com.zaxxer.hikari.HikariDataSource;
import dev.promisegraph.team.InMemoryTeamStore;
import dev.promisegraph.team.JdbcTeamStore;
import dev.promisegraph.team.SecretBox;
import dev.promisegraph.team.TeamStore;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Team mode ({@code PG_AUTH=google}): people, their sign-ins, and the key that protects them. */
@Configuration
@ConditionalOnProperty(prefix = "promisegraph.auth", name = "mode", havingValue = "google")
public class TeamConfig {

    @Bean
    TeamStore teamStore(ObjectProvider<HikariDataSource> ds) {
        HikariDataSource d = ds.getIfAvailable();
        return d == null ? new InMemoryTeamStore() : new JdbcTeamStore(new NamedParameterJdbcTemplate(d));
    }

    @Bean
    SecretBox secretBox(AuthProperties auth) throws IOException {
        return SecretBox.load(auth.secretKey(), Path.of(auth.secretKeyFile()));
    }
}
