package com.livevote.results.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.livevote.results.config.RabbitMqConfig;
import com.livevote.results.service.ResultsProjectionService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

@ExtendWith(MockitoExtension.class)
class ResultsEventConsumerTest {

    @Mock
    private ResultsProjectionService projectionService;

    private ResultsEventConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new ResultsEventConsumer(objectMapper, projectionService);
    }

    @Test
    void shouldRouteValidPollCreatedAndVoteCastPayloads() {
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
        verify(projectionService).handleVoteCast(any(VoteCastEvent.class));
    }

    @Test
    void shouldRejectMalformedJsonUnsupportedVersionAndMissingFieldsToDlq() {
        assertThatThrownBy(() -> consumer.onMessage(buildMessage(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, "{invalid-json")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

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
    }

    private Message buildMessage(String routingKey, String body) {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(routingKey);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }
}
