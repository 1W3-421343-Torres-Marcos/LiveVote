package com.livevote.results;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livevote.results.config.RabbitMqConfig;
import com.livevote.results.dto.PollResultsResponse;
import com.livevote.results.event.PollCreatedEvent;
import com.livevote.results.event.PollCreatedOptionEvent;
import com.livevote.results.event.VoteCastEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.messaging.retry-delay-ms=200",
                "app.messaging.local-retry-initial-ms=50",
                "app.messaging.local-retry-max-attempts=2"
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

        // 5. Missing poll returns 404 & readiness probe returns UP
        webTestClient.get()
                .uri("/api/results/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .value(body -> assertThat(body).containsEntry("status", "UP"));
    }

    private void sendJson(String routingKey, Object payload) throws Exception {
        byte[] bytes = objectMapper.writeValueAsBytes(payload);
        Message message = MessageBuilder.withBody(bytes)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
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
}
