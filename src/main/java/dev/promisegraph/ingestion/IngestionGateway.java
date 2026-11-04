package dev.promisegraph.ingestion;

import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Message;
import dev.promisegraph.privacy.Redactor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects messages per channel and flushes a batch when it is full or old enough. Each batch
 * becomes one extraction workflow whose ID is derived from its message IDs, so a batch
 * delivered twice starts only one workflow.
 */
public final class IngestionGateway {

    /** Starts the extraction workflow for a batch. Must tolerate being called twice for the same batch. */
    @FunctionalInterface
    public interface BatchStarter {
        void start(String workflowId, ExtractionInput input);
    }

    private static final class Buffer {
        final Instant firstSeen;
        final List<Message> messages = new ArrayList<>();

        Buffer(Instant firstSeen) {
            this.firstSeen = firstSeen;
        }
    }

    private final String tenant;
    private final BatchStarter starter;
    private final int maxBatch;
    private final Duration maxWait;
    private final Clock clock;
    private final Map<String, Buffer> buffers = new LinkedHashMap<>();
    /** Cheap in-process dedupe; the database claim in the extraction workflow is the real guard. */
    private final Set<String> seen = new HashSet<>();

    public IngestionGateway(String tenant, BatchStarter starter, int maxBatch, Duration maxWait, Clock clock) {
        if (maxBatch < 1) {
            throw new IllegalArgumentException("maxBatch must be at least 1");
        }
        this.tenant = tenant;
        this.starter = starter;
        this.maxBatch = maxBatch;
        this.maxWait = maxWait;
        this.clock = clock;
    }

    public void ingest(Message message) {
        message.timestamp(); // reject timestamps without an offset early
        boolean full;
        synchronized (this) {
            if (!seen.add(message.id())) {
                return;
            }
            Buffer buffer = buffers.computeIfAbsent(message.channel(), c -> new Buffer(clock.instant()));
            buffer.messages.add(Redactor.redact(message)); // nothing sensitive reaches workflow history
            full = buffer.messages.size() >= maxBatch;
        }
        if (full) {
            flushChannel(message.channel());
        }
    }

    /** Flushes buffers that have waited long enough. Call periodically. */
    public int flushDue() {
        Instant now = clock.instant();
        List<String> due;
        synchronized (this) {
            due = buffers.entrySet().stream()
                    .filter(e -> !now.isBefore(e.getValue().firstSeen.plus(maxWait)))
                    .map(Map.Entry::getKey)
                    .toList();
        }
        due.forEach(this::flushChannel);
        return due.size();
    }

    public int flushAll() {
        List<String> channels;
        synchronized (this) {
            channels = List.copyOf(buffers.keySet());
        }
        channels.forEach(this::flushChannel);
        return channels.size();
    }

    private void flushChannel(String channel) {
        Buffer buffer;
        synchronized (this) {
            buffer = buffers.remove(channel);
        }
        if (buffer == null || buffer.messages.isEmpty()) {
            return;
        }
        String workflowId = Ids.extractionWorkflowId(tenant, buffer.messages.stream().map(Message::id).toList());
        try {
            starter.start(workflowId, new ExtractionInput(tenant, buffer.messages));
        } catch (RuntimeException e) {
            // Put the messages back so nothing is lost; the next flush retries them.
            synchronized (this) {
                Buffer existing = buffers.computeIfAbsent(channel, c -> new Buffer(buffer.firstSeen));
                existing.messages.addAll(0, buffer.messages);
            }
            throw e;
        }
    }
}
