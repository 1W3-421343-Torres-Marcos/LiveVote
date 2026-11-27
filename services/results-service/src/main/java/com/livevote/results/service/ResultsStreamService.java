package com.livevote.results.service;

import com.livevote.results.config.SseProperties;
import com.livevote.results.dto.HeartbeatEvent;
import com.livevote.results.dto.PollResultsResponse;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

/**
 * Manages per-poll in-memory SSE multicast channels, initial snapshot delivery, and heartbeats.
 */
@Service
public class ResultsStreamService {

    private static final Duration SSE_RETRY_DELAY = Duration.ofSeconds(3);
    private static final Logger log = LoggerFactory.getLogger(ResultsStreamService.class);

    private final ResultsProjectionService projectionService;
    private final Duration heartbeatInterval;
    private final int clientBufferSize;
    private final Scheduler heartbeatScheduler;
    private final ConcurrentHashMap<UUID, PollStreamChannel> channels = new ConcurrentHashMap<>();

    @Autowired
    public ResultsStreamService(
            ResultsProjectionService projectionService,
            SseProperties sseProperties,
            MeterRegistry meterRegistry
    ) {
        this(
                projectionService,
                Duration.ofMillis(sseProperties.getHeartbeatIntervalMs()),
                sseProperties.getClientBufferSize(),
                null,
                meterRegistry
        );
    }

    ResultsStreamService(
            ResultsProjectionService projectionService,
            Duration heartbeatInterval,
            int clientBufferSize,
            Scheduler heartbeatScheduler
    ) {
        this(projectionService, heartbeatInterval, clientBufferSize, heartbeatScheduler, new SimpleMeterRegistry());
    }

    ResultsStreamService(
            ResultsProjectionService projectionService,
            Duration heartbeatInterval,
            int clientBufferSize,
            Scheduler heartbeatScheduler,
            MeterRegistry meterRegistry
    ) {
        if (heartbeatInterval == null || heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("SSE heartbeat interval must be positive");
        }
        if (clientBufferSize <= 0) {
            throw new IllegalArgumentException("SSE client buffer size must be positive");
        }
        this.projectionService = projectionService;
        this.heartbeatInterval = heartbeatInterval;
        this.clientBufferSize = clientBufferSize;
        this.heartbeatScheduler = heartbeatScheduler;

        Gauge.builder("livevote.sse.active.channels", this, ResultsStreamService::activePollChannelCount)
                .description("Number of active poll SSE channels")
                .register(meterRegistry);
        Gauge.builder("livevote.sse.active.subscribers", this, ResultsStreamService::activeTotalSubscriberCount)
                .description("Total number of active SSE subscribers")
                .register(meterRegistry);
    }

    /**
     * Pushes a confirmed vote projection update to active subscribers of that poll.
     */
    public void publishUpdate(PollResultsResponse update) {
        PollStreamChannel channel = channels.get(update.pollId());
        if (channel == null) {
            return;
        }
        Sinks.EmitResult result;
        do {
            result = channel.sink.tryEmitNext(update);
        } while (result == Sinks.EmitResult.FAIL_NON_SERIALIZED);

        if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            log.debug("Failed to emit SSE update for pollId={} emitResult={}", update.pollId(), result);
        }
    }

    /**
     * Opens an SSE stream with an immediate snapshot, live updates, and periodic heartbeats.
     */
    public Flux<ServerSentEvent<Object>> streamResults(UUID pollId) {
        return Flux.defer(() -> {
            PollStreamChannel channel = acquireChannel(pollId);
            AtomicBoolean released = new AtomicBoolean(false);
            Runnable releaseOnce = () -> {
                if (released.compareAndSet(false, true)) {
                    releaseChannel(pollId, channel);
                }
            };

            Sinks.Many<PollResultsResponse> liveBuffer = Sinks.many()
                    .unicast()
                    .onBackpressureBuffer(Queues.<PollResultsResponse>get(clientBufferSize).get());
            Disposable channelSubscription = channel.sink.asFlux()
                    .subscribe(update -> emitToClientBuffer(pollId, liveBuffer, update));

            // Offload blocking JPA read onto boundedElastic while liveBuffer captures concurrent votes.
            Mono<PollResultsResponse> snapshotMono = Mono.fromCallable(() -> projectionService.getResults(pollId))
                    .subscribeOn(Schedulers.boundedElastic());

            return snapshotMono.flatMapMany(snapshot -> {
                ServerSentEvent<Object> initialEvent = ServerSentEvent.<Object>builder(snapshot)
                        .event("results")
                        .retry(SSE_RETRY_DELAY)
                        .build();

                Flux<ServerSentEvent<Object>> liveEvents = liveBuffer.asFlux()
                        .filter(update -> update.totalVotes() > snapshot.totalVotes()
                                || update.status() != snapshot.status())
                        .map(update -> ServerSentEvent.<Object>builder((Object) update)
                                .event("results")
                                .build());

                Flux<ServerSentEvent<Object>> heartbeats = heartbeatTicks()
                        .map(tick -> ServerSentEvent.<Object>builder(new HeartbeatEvent(Instant.now()))
                                .event("heartbeat")
                                .build());

                return Flux.concat(Flux.just(initialEvent), Flux.merge(liveEvents, heartbeats));
            }).onErrorResume(ex -> {
                // Evict channel before signaling error downstream so 404/error observers see zero lingering channels.
                channelSubscription.dispose();
                releaseOnce.run();
                return Flux.error(ex);
            }).doFinally(signalType -> {
                channelSubscription.dispose();
                releaseOnce.run();
            });
        });
    }

    public int activeSubscriberCount(UUID pollId) {
        PollStreamChannel channel = channels.get(pollId);
        return channel != null ? channel.subscribers.get() : 0;
    }

    public int activePollChannelCount() {
        return channels.size();
    }

    public int activeTotalSubscriberCount() {
        return channels.values().stream()
                .mapToInt(channel -> channel.subscribers.get())
                .sum();
    }

    private PollStreamChannel acquireChannel(UUID pollId) {
        return channels.compute(pollId, (id, existing) -> {
            PollStreamChannel target = existing != null ? existing : new PollStreamChannel();
            target.subscribers.incrementAndGet();
            return target;
        });
    }

    private void releaseChannel(UUID pollId, PollStreamChannel channel) {
        channels.computeIfPresent(pollId, (id, existing) -> {
            if (existing != channel) {
                return existing;
            }
            return existing.subscribers.decrementAndGet() <= 0 ? null : existing;
        });
    }

    private void emitToClientBuffer(UUID pollId, Sinks.Many<PollResultsResponse> liveBuffer, PollResultsResponse update) {
        Sinks.EmitResult result;
        do {
            result = liveBuffer.tryEmitNext(update);
        } while (result == Sinks.EmitResult.FAIL_NON_SERIALIZED);

        if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
            log.debug("SSE client buffer full for pollId={}; skipping intermediate update", pollId);
        }
    }

    private Flux<Long> heartbeatTicks() {
        return heartbeatScheduler != null
                ? Flux.interval(heartbeatInterval, heartbeatScheduler)
                : Flux.interval(heartbeatInterval);
    }

    private static final class PollStreamChannel {
        private final Sinks.Many<PollResultsResponse> sink = Sinks.many().multicast().directBestEffort();
        private final AtomicInteger subscribers = new AtomicInteger(0);
    }
}
