package com.livevote.votes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livevote.votes.config.RabbitMqConfig;
import com.livevote.votes.dto.AuthResponse;
import com.livevote.votes.dto.CastVoteRequest;
import com.livevote.votes.dto.CreatePollRequest;
import com.livevote.votes.dto.LoginRequest;
import com.livevote.votes.dto.PollResponse;
import com.livevote.votes.dto.RegisterRequest;
import com.livevote.votes.dto.VoteResponse;
import com.livevote.votes.entity.PollStatus;
import com.livevote.votes.exception.ApiErrorResponse;
import com.livevote.votes.security.AuthenticationFilter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class VotesServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13.7-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullPresenterAndVotingLifecycleAgainstRealPostgresAndRabbitMq() throws Exception {
        String testQueueName = "test.votes.events." + UUID.randomUUID();
        Queue testQueue = QueueBuilder.nonDurable(testQueueName).build();
        amqpAdmin.declareQueue(testQueue);
        amqpAdmin.declareBinding(BindingBuilder.bind(testQueue)
                .to(new TopicExchange(RabbitMqConfig.EVENTS_EXCHANGE))
                .with("#"));

        try {
            // 1. Register & duplicate check
            RegisterRequest ownerReg = new RegisterRequest("owner@livevote.test", "StrongPass123!");
            ResponseEntity<AuthResponse> registerResp = restTemplate.postForEntity(
                    "/api/auth/register", ownerReg, AuthResponse.class);
            assertThat(registerResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(registerResp.getBody()).isNotNull();
            String ownerToken = registerResp.getBody().token();
            UUID ownerId = registerResp.getBody().userId();

            ResponseEntity<ApiErrorResponse> duplicateRegisterResp = restTemplate.postForEntity(
                    "/api/auth/register", ownerReg, ApiErrorResponse.class);
            assertThat(duplicateRegisterResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            // 2. Login valid and invalid
            ResponseEntity<AuthResponse> loginResp = restTemplate.postForEntity(
                    "/api/auth/login",
                    new LoginRequest("owner@livevote.test", "StrongPass123!"),
                    AuthResponse.class);
            assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<ApiErrorResponse> badLoginResp = restTemplate.postForEntity(
                    "/api/auth/login",
                    new LoginRequest("owner@livevote.test", "WrongPass!"),
                    ApiErrorResponse.class);
            assertThat(badLoginResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            // 3. Protected endpoint without JWT or with spoofed X-User-Id must fail with 401
            ResponseEntity<ApiErrorResponse> unauthResp = restTemplate.getForEntity(
                    "/api/polls/mine", ApiErrorResponse.class);
            assertThat(unauthResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            HttpHeaders spoofedHeaders = new HttpHeaders();
            spoofedHeaders.set(AuthenticationFilter.HEADER_USER_ID, ownerId.toString());
            ResponseEntity<ApiErrorResponse> spoofedResp = restTemplate.exchange(
                    "/api/polls/mine",
                    HttpMethod.GET,
                    new HttpEntity<>(spoofedHeaders),
                    ApiErrorResponse.class);
            assertThat(spoofedResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            // 4. Create poll validation (1 option and 7 options fail; 3 options succeed & publish PollCreated)
            HttpHeaders ownerHeaders = bearerHeaders(ownerToken);

            ResponseEntity<ApiErrorResponse> tooFewOptions = restTemplate.postForEntity(
                    "/api/polls",
                    new HttpEntity<>(new CreatePollRequest("Too few?", List.of("Only one")), ownerHeaders),
                    ApiErrorResponse.class);
            assertThat(tooFewOptions.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            ResponseEntity<ApiErrorResponse> tooManyOptions = restTemplate.postForEntity(
                    "/api/polls",
                    new HttpEntity<>(
                            new CreatePollRequest("Too many?", List.of("1", "2", "3", "4", "5", "6", "7")),
                            ownerHeaders),
                    ApiErrorResponse.class);
            assertThat(tooManyOptions.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            ResponseEntity<PollResponse> createPollResp = restTemplate.postForEntity(
                    "/api/polls",
                    new HttpEntity<>(
                            new CreatePollRequest("Best backend language?", List.of("Java", "Go", "Rust")),
                            ownerHeaders),
                    PollResponse.class);
            assertThat(createPollResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            PollResponse createdPoll = createPollResp.getBody();
            assertThat(createdPoll).isNotNull();
            assertThat(createdPoll.status()).isEqualTo(PollStatus.OPEN);
            assertThat(createdPoll.options()).hasSize(3);

            Message pollCreatedMsg = rabbitTemplate.receive(testQueueName, 5000);
            assertThat(pollCreatedMsg).isNotNull();
            assertThat(pollCreatedMsg.getMessageProperties().getReceivedRoutingKey())
                    .isEqualTo(RabbitMqConfig.ROUTING_KEY_POLL_CREATED);
            assertThat(pollCreatedMsg.getMessageProperties().getHeaders()).doesNotContainKey("__TypeId__");
            JsonNode pollCreatedJson = objectMapper.readTree(pollCreatedMsg.getBody());
            assertThat(pollCreatedJson.get("eventVersion").asInt()).isEqualTo(1);
            assertThat(pollCreatedJson.get("pollId").asText()).isEqualTo(createdPoll.id().toString());
            assertThat(pollCreatedJson.get("options")).hasSize(3);
            assertThat(pollCreatedJson.has("ownerId")).isFalse();
            assertThat(pollCreatedJson.has("email")).isFalse();

            // 5. List my polls & public poll lookup
            ResponseEntity<List<PollResponse>> myPollsResp = restTemplate.exchange(
                    "/api/polls/mine",
                    HttpMethod.GET,
                    new HttpEntity<>(ownerHeaders),
                    new ParameterizedTypeReference<>() {});
            assertThat(myPollsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(myPollsResp.getBody()).extracting(PollResponse::id).containsExactly(createdPoll.id());

            ResponseEntity<PollResponse> publicPollResp = restTemplate.getForEntity(
                    "/api/polls/" + createdPoll.id(), PollResponse.class);
            assertThat(publicPollResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(publicPollResp.getBody().title()).isEqualTo("Best backend language?");

            // 6. Anonymous voting: 201 (publishes VoteCast), 409 duplicate device (no event), 404 unknown option
            UUID chosenOptionId = createdPoll.options().get(0).id();
            String deviceId = UUID.randomUUID().toString();

            ResponseEntity<VoteResponse> voteResp = restTemplate.postForEntity(
                    "/api/polls/" + createdPoll.id() + "/votes",
                    new CastVoteRequest(chosenOptionId, deviceId),
                    VoteResponse.class);
            assertThat(voteResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(voteResp.getBody()).isNotNull();
            assertThat(voteResp.getBody().pollId()).isEqualTo(createdPoll.id());

            Message voteCastMsg = rabbitTemplate.receive(testQueueName, 5000);
            assertThat(voteCastMsg).isNotNull();
            assertThat(voteCastMsg.getMessageProperties().getReceivedRoutingKey())
                    .isEqualTo(RabbitMqConfig.ROUTING_KEY_VOTE_CAST);
            JsonNode voteCastJson = objectMapper.readTree(voteCastMsg.getBody());
            assertThat(voteCastJson.get("eventVersion").asInt()).isEqualTo(1);
            assertThat(voteCastJson.get("voteId").asText()).isEqualTo(voteResp.getBody().voteId().toString());
            assertThat(voteCastJson.get("pollId").asText()).isEqualTo(createdPoll.id().toString());
            assertThat(voteCastJson.get("optionId").asText()).isEqualTo(chosenOptionId.toString());

            ResponseEntity<ApiErrorResponse> duplicateVoteResp = restTemplate.postForEntity(
                    "/api/polls/" + createdPoll.id() + "/votes",
                    new CastVoteRequest(createdPoll.options().get(1).id(), deviceId),
                    ApiErrorResponse.class);
            assertThat(duplicateVoteResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            ResponseEntity<ApiErrorResponse> badOptionResp = restTemplate.postForEntity(
                    "/api/polls/" + createdPoll.id() + "/votes",
                    new CastVoteRequest(UUID.randomUUID(), UUID.randomUUID().toString()),
                    ApiErrorResponse.class);
            assertThat(badOptionResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            // 7. Close poll: another user -> 403; owner -> 200; vote after close -> 422
            ResponseEntity<AuthResponse> otherReg = restTemplate.postForEntity(
                    "/api/auth/register",
                    new RegisterRequest("other@livevote.test", "StrongPass123!"),
                    AuthResponse.class);
            String otherToken = otherReg.getBody().token();

            ResponseEntity<ApiErrorResponse> forbiddenCloseResp = restTemplate.postForEntity(
                    "/api/polls/" + createdPoll.id() + "/close",
                    new HttpEntity<>(bearerHeaders(otherToken)),
                    ApiErrorResponse.class);
            assertThat(forbiddenCloseResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            ResponseEntity<PollResponse> closeResp = restTemplate.postForEntity(
                    "/api/polls/" + createdPoll.id() + "/close",
                    new HttpEntity<>(ownerHeaders),
                    PollResponse.class);
            assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(closeResp.getBody().status()).isEqualTo(PollStatus.CLOSED);
            assertThat(closeResp.getBody().closedAt()).isNotNull();

            ResponseEntity<ApiErrorResponse> closedVoteResp = restTemplate.postForEntity(
                    "/api/polls/" + createdPoll.id() + "/votes",
                    new CastVoteRequest(chosenOptionId, UUID.randomUUID().toString()),
                    ApiErrorResponse.class);
            assertThat(closedVoteResp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            // Rejected/failed votes must never publish events
            assertThat(rabbitTemplate.receive(testQueueName, 300)).isNull();

            // 8. Readiness probe reflects healthy postgres-votes DB
            ResponseEntity<Map<String, Object>> readinessResp = restTemplate.exchange(
                    "/actuator/health/readiness",
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    new ParameterizedTypeReference<>() {});
            assertThat(readinessResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(readinessResp.getBody()).containsEntry("status", "UP");
        } finally {
            amqpAdmin.deleteQueue(testQueueName);
        }
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
