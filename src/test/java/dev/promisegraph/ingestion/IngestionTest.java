package dev.promisegraph.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.promisegraph.config.PromiseGraphProperties;
import dev.promisegraph.domain.Message;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class IngestionTest {

    private static Message msg(int i, String channel) {
        return new Message("m" + i, channel, "neha", "2026-11-02T10:00:00+05:30", "message " + i, "demo");
    }

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T04:30:00Z"));

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    void batchesPerChannelFlushesWhenFullOrOldAndDedupes() {
        List<List<String>> started = new ArrayList<>();
        MutableClock clock = new MutableClock();
        IngestionGateway gw = new IngestionGateway("demo",
                (id, input) -> started.add(input.messages().stream().map(Message::id).toList()), 3, Duration.ofSeconds(30), clock);

        for (int i = 0; i < 4; i++) {
            gw.ingest(msg(i, "platform"));
        }
        gw.ingest(msg(0, "platform")); // duplicate delivery
        gw.ingest(msg(9, "analytics"));
        assertThat(started).containsExactly(List.of("m0", "m1", "m2"));
        assertThat(gw.flushDue()).isZero();

        clock.now.set(clock.now.get().plusSeconds(31));
        assertThat(gw.flushDue()).isEqualTo(2);
        assertThat(started.subList(1, 3)).containsExactlyInAnyOrder(List.of("m3"), List.of("m9"));
    }

    @Test
    void keepsMessagesWhenStartFails() {
        List<List<String>> calls = new ArrayList<>();
        IngestionGateway gw = new IngestionGateway("demo", (id, input) -> {
            calls.add(input.messages().stream().map(Message::id).toList());
            if (calls.size() == 1) {
                throw new IllegalStateException("temporal unavailable");
            }
        }, 10, Duration.ofSeconds(30), Clock.systemUTC());

        gw.ingest(msg(1, "platform"));
        assertThatThrownBy(gw::flushAll).isInstanceOf(IllegalStateException.class);
        gw.flushAll();
        assertThat(calls).containsExactly(List.of("m1"), List.of("m1"));
    }

    @Test
    void rejectsTimestampsWithoutOffset() {
        IngestionGateway gw = new IngestionGateway("demo", (id, input) -> {}, 10, Duration.ofSeconds(30), Clock.systemUTC());
        assertThatThrownBy(() -> gw.ingest(new Message("m1", "c", "s", "2026-11-02T10:00:00", "x", "demo")))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    @Test
    void webhookChecksTokenAndFields() {
        List<String> received = new ArrayList<>();
        IngestionGateway gw = new IngestionGateway("demo",
                (id, input) -> input.messages().forEach(m -> received.add(m.id())), 1, Duration.ofSeconds(30), Clock.systemUTC());
        var props = new PromiseGraphProperties("demo", "memory", "rules", "m", 0.8, 1, "s3cret",
                new PromiseGraphProperties.Temporal("localhost:7233", "default", false),
                new PromiseGraphProperties.Gateway(1, Duration.ofSeconds(30)));
        WebhookController controller = new WebhookController(gw, props);
        var good = new WebhookController.IncomingMessage("m1", "platform", "neha", "2026-11-02T10:00:00+05:30", "I'll ship it by Friday.");

        assertThat(controller.receive("Bearer s3cret", good).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.receive("Bearer wrong", good).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.receive("Bearer s3cret",
                new WebhookController.IncomingMessage("m2", "platform", "neha", "2026-11-02T10:00:00", "x")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.receive("Bearer s3cret",
                new WebhookController.IncomingMessage("m3", null, null, null, null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(received).containsExactly("m1");
    }
}
