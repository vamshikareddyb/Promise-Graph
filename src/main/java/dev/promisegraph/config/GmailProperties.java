package dev.promisegraph.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for reading a personal Gmail account, bound from {@code promisegraph.gmail.*}.
 *
 * @param credentialsFile the OAuth client JSON downloaded from Google Cloud
 * @param tokenFile where {@code ./gradlew gmailAuth} saved the read-only sign-in
 * @param backfillDays how far back the first sync reads
 */
@ConfigurationProperties("promisegraph.gmail")
public record GmailProperties(boolean enabled, String credentialsFile, String tokenFile, Duration pollInterval, int backfillDays) {

    public GmailProperties {
        if (pollInterval == null || pollInterval.compareTo(Duration.ofSeconds(10)) < 0) {
            pollInterval = Duration.ofSeconds(60);
        }
        if (backfillDays < 1 || backfillDays > 30) {
            backfillDays = 2;
        }
    }
}
