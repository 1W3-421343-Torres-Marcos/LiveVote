package com.livevote.results;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livevote.results.config.RabbitMqConfig;
import com.livevote.results.dto.PollResultsResponse;
import com.livevote.results.event.PollCreatedEvent;
import com.livevote.results.event.PollCreatedOptionEvent;
import com.livevote.results.event.VoteCastEvent;
import com.livevote.results.service.ResultsStreamService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.messaging.retry-delay-ms=200",
                "app.messaging.local-retry-initial-ms=50",
                "app.messaging.local-retry-max-attempts=2",
                "app.sse.heartbeat-interval-ms=600"
        }
)
@Testcontainers
class ResultsServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13.7-alpine");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ResultsStreamService resultsStreamService;

    @Test
    void shouldProjectEventsIdempotentlyRetryOutOfOrderAndRoutePoisonMessagesToDlq() throws Exception {
        // 1. PollCreated -> GET /api/results/{id} returns 200 with zero counts
        UUID pollId = UUID.randomUUID();
        UUID optionA = UUID.randomUUID();
        UUID optionB = UUID.randomUUID();
        PollCreatedEvent pollCreated = new PollCreatedEvent(
                1,
                pollId,
                "Preferred architecture?",
                List.of(
                        new PollCreatedOptionEvent(optionA, "Event-driven"),
                        new PollCreatedOptionEvent(optionB, "Monolith")
                ),
                Instant.now()
        );
        sendJson(RabbitMqConfig.ROUTING_KEY_POLL_CREATED, pollCreated);

        PollResultsResponse initial = awaitResults(pollId, r -> r != null && r.options().size() == 2);
        assertThat(initial.totalVotes()).isZero();
        assertThat(initial.options().get(0).votes()).isZero();

        // 2. VoteCast + duplicate VoteCast -> idempotency keeps vote count at 1
        UUID voteId = UUID.randomUUID();
        VoteCastEvent voteEvent = new VoteCastEvent(1, voteId, pollId, optionA, Instant.now());
        sendJson(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, voteEvent);

        PollResultsResponse afterFirstVote = awaitResults(pollId, r -> r != null && r.totalVotes() == 1L);
        assertThat(afterFirstVote.options().get(0).votes()).isEqualTo(1L);

        // Redeliver exact same voteId twice and verify count stays 1 even after processing another vote
        sendJson(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, voteEvent);
        sendJson(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, voteEvent);
        UUID secondVoteId = UUID.randomUUID();
        sendJson(RabbitMqConfig.ROUTING_KEY_VOTE_CAST,
                new VoteCastEvent(1, secondVoteId, pollId, optionB, Instant.now()));

        PollResultsResponse afterDuplicateAndSecond = awaitResults(pollId, r -> r != null && r.totalVotes() == 2L);
        assertThat(afterDuplicateAndSecond.options().get(0).votes()).isEqualTo(1L);
        assertThat(afterDuplicateAndSecond.options().get(1).votes()).isEqualTo(1L);

        // 3. Out-of-order VoteCast arrives before PollCreated -> delayed retry converges to 1
        UUID poll2Id = UUID.randomUUID();
        UUID poll2Option = UUID.randomUUID();
        sendJson(RabbitMqConfig.ROUTING_KEY_VOTE_CAST,
                new VoteCastEvent(1, UUID.randomUUID(), poll2Id, poll2Option, Instant.now()));
        sendJson(RabbitMqConfig.ROUTING_KEY_POLL_CREATED, new PollCreatedEvent(
                1,
                poll2Id,
                "Delayed poll",
                List.of(new PollCreatedOptionEvent(poll2Option, "Only option")),
                Instant.now()
        ));

        PollResultsResponse poll2Results = awaitResults(poll2Id, r -> r != null && r.totalVotes() == 1L);
        assertThat(poll2Results.options().get(0).votes()).isEqualTo(1L);

        // 4. Poison messages (corrupt JSON & unknown eventVersion) route straight to DLQ
        sendRaw(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, "{corrupt-json");
        Message dlqMalformed = rabbitTemplate.receive(RabbitMqConfig.DLQ_QUEUE, 5000);
        assertThat(dlqMalformed).isNotNull();

        sendJson(RabbitMqConfig.ROUTING_KEY_VOTE_CAST,
                new VoteCastEvent(99, UUID.randomUUID(), pollId, optionA, Instant.now()));
        Message dlqWrongVersion = rabbitTemplate.receive(RabbitMqConfig.DLQ_QUEUE, 5000);
        assertThat(dlqWrongVersion).isNotNull();

        // 5. Missing poll returns 404, X-Trace-Id echoes, and Actuator probes/metrics return UP
        webTestClient.get()
                .uri("/api/results/{id}", UUID.randomUUID())
                .header("X-Trace-Id", "trace-res-http-1")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals("X-Trace-Id", "trace-res-http-1");

        webTestClient.get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .value(body -> assertThat(body).containsEntry("status", "UP"));

        webTestClient.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .value(body -> {
                    assertThat(body).containsEntry("status", "UP");
                    assertThat(body).containsKey("components");
                });

        webTestClient.get()
                .uri("/actuator/metrics/livevote.events.consumed.total")
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/actuator/metrics/livevote.sse.active.channels")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldStreamSnapshotLiveUpdatesHeartbeatAndCleanUpOnCancel() throws Exception {
        UUID pollId = UUID.randomUUID();
        UUID optionA = UUID.randomUUID();
        UUID optionB = UUID.randomUUID();
        sendJson(RabbitMqConfig.ROUTING_KEY_POLL_CREATED, new PollCreatedEvent(
                1,
                pollId,
                "Live SSE Integration Poll",
                List.of(
                        new PollCreatedOptionEvent(optionA, "Alpha"),
                        new PollCreatedOptionEvent(optionB, "Beta")
                ),
                Instant.now()
        ));

        awaitResults(pollId, r -> r != null && r.totalVotes() == 0L);

        WebTestClient sseClient = webTestClient.mutate()
                .responseTimeout(Duration.ofSeconds(10))
                .build();

        FluxExchangeResult<ServerSentEvent<String>> streamResult = sseClient.get()
                .uri("/api/results/{id}/stream", pollId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectHeader().valueEquals("X-Accel-Buffering", "no")
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {});

        List<PollResultsResponse> resultsEvents = new CopyOnWriteArrayList<>();
        AtomicInteger heartbeatCount = new AtomicInteger(0);
        AtomicBoolean vote2Seen = new AtomicBoolean(false);
        UUID vote1Id = UUID.randomUUID();
        UUID vote2Id = UUID.randomUUID();

        Flux<ServerSentEvent<String>> boundedStream = streamResult.getResponseBody()
                .doOnNext(event -> {
                    if ("results".equals(event.event()) && event.data() != null) {
                        try {
                            PollResultsResponse parsed = objectMapper.readValue(event.data(), PollResultsResponse.class);
                            resultsEvents.add(parsed);
                            if (parsed.totalVotes() == 2L) {
                                vote2Seen.set(true);
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    } else if ("heartbeat".equals(event.event())) {
                        heartbeatCount.incrementAndGet();
                    }
                })
                .takeUntil(ignored -> vote2Seen.get() && heartbeatCount.get() >= 1);

        StepVerifier.create(boundedStream)
                .assertNext(first -> {
                    assertThat(first.event()).isEqualTo("results");
                    assertThat(first.retry()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(resultsEvents).hasSize(1);
                    assertThat(resultsEvents.get(0).totalVotes()).isZero();
                })
                .then(() -> {
                    try {
                        sendJson(RabbitMqConfig.ROUTING_KEY_VOTE_CAST,
                                new VoteCastEvent(1, vote1Id, pollId, optionA, Instant.now()));
                        sendJson(RabbitMqConfig.ROUTING_KEY_VOTE_CAST,
                                new VoteCastEvent(1, vote1Id, pollId, optionA, Instant.now()));
                        sendJson(RabbitMqConfig.ROUTING_KEY_VOTE_CAST,
                                new VoteCastEvent(1, vote2Id, pollId, optionB, Instant.now()));
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .thenConsumeWhile(ignored -> true)
                .verifyComplete();

        assertThat(resultsEvents).extracting(PollResultsResponse::totalVotes).containsExactly(0L, 1L, 2L);
        assertThat(heartbeatCount.get()).isGreaterThanOrEqualTo(1);

        awaitCondition(() -> resultsStreamService.activeSubscriberCount(pollId) == 0
                && resultsStreamService.activePollChannelCount() == 0);

        webTestClient.get()
                .uri("/api/results/{id}/stream", UUID.randomUUID())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
    }

    private void sendJson(String routingKey, Object payload) throws Exception {
        byte[] bytes = objectMapper.writeValueAsBytes(payload);
        Message message = MessageBuilder.withBody(bytes)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader("X-Trace-Id", "trace-results-int-1")
                .build();
        rabbitTemplate.send(RabbitMqConfig.EVENTS_EXCHANGE, routingKey, message);
    }

    private void sendRaw(String routingKey, String rawPayload) {
        Message message = MessageBuilder.withBody(rawPayload.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.send(RabbitMqConfig.EVENTS_EXCHANGE, routingKey, message);
    }

    private PollResultsResponse awaitResults(UUID pollId, Predicate<PollResultsResponse> condition)
            throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        PollResultsResponse latest = null;
        while (Instant.now().isBefore(deadline)) {
            EntityExchangeResult<byte[]> result = webTestClient.get()
                    .uri("/api/results/{id}", pollId)
                    .exchange()
                    .expectBody(byte[].class)
                    .returnResult();
            if (result.getStatus().is2xxSuccessful() && result.getResponseBody() != null) {
                latest = objectMapper.readValue(result.getResponseBody(), PollResultsResponse.class);
                if (condition.test(latest)) {
                    return latest;
                }
            }
            Thread.sleep(75);
        }
        throw new AssertionError("Timed out waiting for poll results condition for pollId=" + pollId + ", latest=" + latest);
    }

    private void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for condition");
    }
}
