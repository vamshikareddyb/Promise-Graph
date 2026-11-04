package dev.promisegraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/** A computer set to India must not break the database connection (Postgres 16 rejects "Asia/Calcutta"). */
class TimeZoneTest {

    @Test
    void theAppRunsInUtcWhateverTheComputerSays() {
        TimeZone before = TimeZone.getDefault();
        String property = System.getProperty("user.timezone");
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Calcutta"));
            PromiseGraphApplication.useUtc();
            assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
        } finally {
            TimeZone.setDefault(before);
            if (property != null) {
                System.setProperty("user.timezone", property);
            }
        }
    }
}
