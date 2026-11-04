package dev.promisegraph.config;

import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.store.DeadLetterStore;
import dev.promisegraph.store.NegotiationStore;
import dev.promisegraph.store.Repository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gauges for the dashboard: promises by status, open dead letters and running negotiations. Counts
 * are read from storage at most every 30 seconds, however often Prometheus scrapes. Counters for
 * events, notifications, negotiations, dead letters and restarts are recorded where they happen.
 */
@Configuration
public class MetricsConfig {

    static final Duration REFRESH = Duration.ofSeconds(30);

    /** The latest counts, refreshed lazily. */
    static final class Counts {
        private final String tenant;
        private final Repository repo;
        private final DeadLetterStore deadLetters;
        private final NegotiationStore negotiations;
        private Instant at = Instant.EPOCH;
        private Map<PromiseStatus, Integer> byStatus = new EnumMap<>(PromiseStatus.class);
        private int openDeadLetters;
        private int openNegotiations;

        Counts(String tenant, Repository repo, DeadLetterStore deadLetters, NegotiationStore negotiations) {
            this.tenant = tenant;
            this.repo = repo;
            this.deadLetters = deadLetters;
            this.negotiations = negotiations;
        }

        private synchronized void refresh() {
            if (Instant.now().isBefore(at.plus(REFRESH))) {
                return;
            }
            Map<PromiseStatus, Integer> next = new EnumMap<>(PromiseStatus.class);
            for (Promise p : repo.promisesByStatus(tenant, EnumSet.allOf(PromiseStatus.class))) {
                next.merge(p.status(), 1, Integer::sum);
            }
            byStatus = next;
            openDeadLetters = deadLetters.list(tenant, true).size();
            openNegotiations = (int) negotiations.list(tenant).stream().filter(NegotiationRecord::stillOpen).count();
            at = Instant.now();
        }

        synchronized double promises(PromiseStatus s) {
            refresh();
            return byStatus.getOrDefault(s, 0);
        }

        synchronized double deadLetters() {
            refresh();
            return openDeadLetters;
        }

        synchronized double negotiations() {
            refresh();
            return openNegotiations;
        }
    }

    @Bean
    MeterBinder promiseGraphGauges(PromiseGraphProperties props, Repository repo, DeadLetterStore deadLetters, NegotiationStore negotiations) {
        Counts counts = new Counts(props.tenant(), repo, deadLetters, negotiations);
        return registry -> {
            for (PromiseStatus s : PromiseStatus.values()) {
                Gauge.builder("promisegraph.promises", counts, c -> c.promises(s)).tag("status", s.dbValue())
                        .description("Promises by status").register(registry);
            }
            Gauge.builder("promisegraph.dead_letters.open", counts, Counts::deadLetters).description("Dead letters waiting for a person")
                    .register(registry);
            Gauge.builder("promisegraph.negotiations.open", counts, Counts::negotiations).description("Negotiations still running")
                    .register(registry);
        };
    }
}
