package com.livevote.results.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livevote.results.config.RabbitMqConfig;
import com.livevote.results.exception.InvalidEventException;
import com.livevote.results.service.ResultsProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes versioned JSON events from RabbitMQ and enforces schema validation before projection.
 */
@Component
public class ResultsEventConsumer {

    private static final int SUPPORTED_EVENT_VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(ResultsEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final ResultsProjectionService projectionService;

    public ResultsEventConsumer(ObjectMapper objectMapper, ResultsProjectionService projectionService) {
        this.objectMapper = objectMapper;
        this.projectionService = projectionService;
    }

    @RabbitListener(queues = RabbitMqConfig.EVENTS_QUEUE, containerFactory = "rabbitListenerContainerFactory")
    public void onMessage(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        if (routingKey == null) {
            throw new AmqpRejectAndDontRequeueException("Missing routing key on event message");
        }

        try {
            switch (routingKey) {
                case RabbitMqConfig.ROUTING_KEY_POLL_CREATED -> {
                    PollCreatedEvent event = parse(message.getBody(), PollCreatedEvent.class);
                    validatePollCreated(event);
                    projectionService.handlePollCreated(event);
                }
                case RabbitMqConfig.ROUTING_KEY_VOTE_CAST -> {
                    VoteCastEvent event = parse(message.getBody(), VoteCastEvent.class);
                    validateVoteCast(event);
                    projectionService.handleVoteCast(event);
                }
                default -> throw new InvalidEventException("Unsupported routing key: " + routingKey);
            }
        } catch (InvalidEventException ex) {
            log.warn("Rejecting poison message routingKey={} reason={}", routingKey, ex.getMessage());
            throw new AmqpRejectAndDontRequeueException(ex.getMessage(), ex);
        }
    }

    private <T> T parse(byte[] body, Class<T> targetType) {
        if (body == null || body.length == 0) {
            throw new InvalidEventException("Empty event payload");
        }
        try {
            return objectMapper.readValue(body, targetType);
        } catch (JsonProcessingException ex) {
            throw new InvalidEventException("Malformed JSON payload", ex);
        } catch (Exception ex) {
            throw new InvalidEventException("Unreadable event payload", ex);
        }
    }

    private void validatePollCreated(PollCreatedEvent event) {
        if (event == null || !Integer.valueOf(SUPPORTED_EVENT_VERSION).equals(event.eventVersion())) {
            throw new InvalidEventException("Unsupported or missing PollCreated eventVersion");
        }
        if (event.pollId() == null || isBlank(event.title()) || event.createdAt() == null) {
            throw new InvalidEventException("Missing required fields in PollCreated event");
        }
        if (event.options() == null || event.options().isEmpty()) {
            throw new InvalidEventException("PollCreated event must contain options");
        }
        for (PollCreatedOptionEvent option : event.options()) {
            if (option == null || option.optionId() == null || isBlank(option.text())) {
                throw new InvalidEventException("Invalid option inside PollCreated event");
            }
        }
    }

    private void validateVoteCast(VoteCastEvent event) {
        if (event == null || !Integer.valueOf(SUPPORTED_EVENT_VERSION).equals(event.eventVersion())) {
            throw new InvalidEventException("Unsupported or missing VoteCast eventVersion");
        }
        if (event.voteId() == null || event.pollId() == null || event.optionId() == null || event.castAt() == null) {
            throw new InvalidEventException("Missing required fields in VoteCast event");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
