package com.livevote.votes.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.livevote.votes.config.RabbitMqConfig;
import com.livevote.votes.config.TraceIdFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class VoteEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;
    private VoteEventPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        meterRegistry = new SimpleMeterRegistry();
        publisher = new VoteEventPublisher(rabbitTemplate, objectMapper, meterRegistry);
    }

    @Test
    void onPollCreatedShouldSendVersionedJsonWithTraceIdWithoutOwnerOrTypeHeaders() throws Exception {
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        PollCreatedEvent event = new PollCreatedEvent(
                1,
                pollId,
                "Best DB?",
                List.of(new PollCreatedOptionEvent(optionId, "PostgreSQL")),
                Instant.parse("2026-11-26T12:00:00Z")
        );

        MDC.put(TraceIdFilter.MDC_TRACE_ID, "trace-vote-123");
        try {
            publisher.onPollCreated(event);
        } finally {
            MDC.remove(TraceIdFilter.MDC_TRACE_ID);
        }

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(
                eq(RabbitMqConfig.EVENTS_EXCHANGE),
                eq(RabbitMqConfig.ROUTING_KEY_POLL_CREATED),
                messageCaptor.capture()
        );

        Message sent = messageCaptor.getValue();
        assertThat(sent.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(sent.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(sent.getMessageProperties().getHeaders()).doesNotContainKey("__TypeId__");
        assertThat(sent.getMessageProperties().getHeaders()).containsEntry(TraceIdFilter.HEADER_TRACE_ID, "trace-vote-123");
        assertThat(sent.getMessageProperties().getHeaders()).containsEntry(TraceIdFilter.MDC_TRACE_ID, "trace-vote-123");
        assertThat(meterRegistry.counter("livevote.events.published.total",
                "routingKey", RabbitMqConfig.ROUTING_KEY_POLL_CREATED,
                "outcome", "success").count()).isEqualTo(1.0d);

        JsonNode json = objectMapper.readTree(sent.getBody());
        assertThat(json.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(json.get("pollId").asText()).isEqualTo(pollId.toString());
        assertThat(json.get("title").asText()).isEqualTo("Best DB?");
        assertThat(json.get("options").get(0).get("optionId").asText()).isEqualTo(optionId.toString());
        assertThat(json.get("options").get(0).get("text").asText()).isEqualTo("PostgreSQL");
        assertThat(json.has("ownerId")).isFalse();
        assertThat(json.has("email")).isFalse();
    }

    @Test
    void onVoteCastShouldSendVersionedJsonPayload() throws Exception {
        UUID voteId = UUID.randomUUID();
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        VoteCastEvent event = new VoteCastEvent(1, voteId, pollId, optionId, Instant.parse("2026-11-26T12:05:00Z"));

        publisher.onVoteCast(event);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(
                eq(RabbitMqConfig.EVENTS_EXCHANGE),
                eq(RabbitMqConfig.ROUTING_KEY_VOTE_CAST),
                messageCaptor.capture()
        );

        JsonNode json = objectMapper.readTree(messageCaptor.getValue().getBody());
        assertThat(json.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(json.get("voteId").asText()).isEqualTo(voteId.toString());
        assertThat(json.get("pollId").asText()).isEqualTo(pollId.toString());
        assertThat(json.get("optionId").asText()).isEqualTo(optionId.toString());
        assertThat(json.get("castAt").asText()).isEqualTo("2026-11-26T12:05:00Z");
    }
}
