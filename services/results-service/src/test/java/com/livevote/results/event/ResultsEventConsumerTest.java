package com.livevote.results.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.livevote.results.config.RabbitMqConfig;
import com.livevote.results.config.TraceIdFilter;
import com.livevote.results.dto.OptionResultResponse;
import com.livevote.results.dto.PollResultsResponse;
import com.livevote.results.entity.PollProjectionStatus;
import com.livevote.results.service.ResultsProjectionService;
import com.livevote.results.service.ResultsStreamService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

@ExtendWith(MockitoExtension.class)
class ResultsEventConsumerTest {

    @Mock
    private ResultsProjectionService projectionService;
    @Mock
    private ResultsStreamService resultsStreamService;

    private SimpleMeterRegistry meterRegistry;
    private ResultsEventConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        meterRegistry = new SimpleMeterRegistry();
        consumer = new ResultsEventConsumer(objectMapper, projectionService, resultsStreamService, meterRegistry);
    }

    @Test
    void shouldRouteValidPollCreatedAndVoteCastPayloadsAndPublishOnlyNewVotes() {
        String pollCreatedJson = """
                {
                  "eventVersion": 1,
                  "pollId": "550e8400-e29b-41d4-a716-446655440000",
                  "title": "Favorite DB?",
                  "options": [
                    {"optionId": "11111111-1111-1111-1111-111111111111", "text": "PostgreSQL"}
                  ],
                  "createdAt": "2026-11-26T12:00:00Z"
                }
                """;
        consumer.onMessage(buildMessage(RabbitMqConfig.ROUTING_KEY_POLL_CREATED, pollCreatedJson));
        verify(projectionService).handlePollCreated(any(PollCreatedEvent.class));

        PollResultsResponse updated = new PollResultsResponse(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                "Favorite DB?",
                PollProjectionStatus.OPEN,
                1L,
                List.of(new OptionResultResponse(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "PostgreSQL",
                        0,
                        1L
                )),
                Instant.parse("2026-11-26T12:00:00Z"),
                null
        );
        when(projectionService.handleVoteCast(any(VoteCastEvent.class)))
                .thenReturn(Optional.of(updated))
                .thenReturn(Optional.empty());

        String voteCastJson = """
                {
                  "eventVersion": 1,
                  "voteId": "99999999-9999-9999-9999-999999999999",
                  "pollId": "550e8400-e29b-41d4-a716-446655440000",
                  "optionId": "11111111-1111-1111-1111-111111111111",
                  "castAt": "2026-11-26T12:05:00Z"
                }
                """;
        consumer.onMessage(buildMessage(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, voteCastJson));
        consumer.onMessage(buildMessage(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, voteCastJson));

        verify(resultsStreamService).publishUpdate(updated);
        verify(resultsStreamService, never()).publishUpdate(null);
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
        assertThat(meterRegistry.counter("livevote.events.consumed.total",
                "routingKey", RabbitMqConfig.ROUTING_KEY_VOTE_CAST,
                "outcome", "processed").count()).isEqualTo(1.0d);
        assertThat(meterRegistry.counter("livevote.events.consumed.total",
                "routingKey", RabbitMqConfig.ROUTING_KEY_VOTE_CAST,
                "outcome", "duplicate").count()).isEqualTo(1.0d);
    }

    @Test
    void shouldRejectMalformedJsonUnsupportedVersionAndMissingFieldsToDlq() {
        assertThatThrownBy(() -> consumer.onMessage(buildMessage(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, "{invalid-json")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();

        String wrongVersionJson = """
                {
                  "eventVersion": 99,
                  "voteId": "99999999-9999-9999-9999-999999999999",
                  "pollId": "550e8400-e29b-41d4-a716-446655440000",
                  "optionId": "11111111-1111-1111-1111-111111111111",
                  "castAt": "2026-11-26T12:05:00Z"
                }
                """;
        assertThatThrownBy(() -> consumer.onMessage(buildMessage(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, wrongVersionJson)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        String missingFieldsJson = """
                {
                  "eventVersion": 1,
                  "pollId": "550e8400-e29b-41d4-a716-446655440000"
                }
                """;
        assertThatThrownBy(() -> consumer.onMessage(buildMessage(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, missingFieldsJson)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> consumer.onMessage(buildMessage("unknown.key", "{}")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }

    private Message buildMessage(String routingKey, String body) {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(routingKey);
        props.setHeader(TraceIdFilter.HEADER_TRACE_ID, "trace-results-1");
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }
}
