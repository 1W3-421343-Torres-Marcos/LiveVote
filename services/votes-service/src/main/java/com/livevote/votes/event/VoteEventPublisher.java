package com.livevote.votes.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livevote.votes.config.RabbitMqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes JSON domain events to RabbitMQ only after the database transaction commits.
 */
@Component
public class VoteEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(VoteEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public VoteEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPollCreated(PollCreatedEvent event) {
        publish(RabbitMqConfig.ROUTING_KEY_POLL_CREATED, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVoteCast(VoteCastEvent event) {
        publish(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, event);
    }

    private void publish(String routingKey, Object event) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(event);
            Message message = MessageBuilder.withBody(payload)
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .build();
            rabbitTemplate.send(RabbitMqConfig.EVENTS_EXCHANGE, routingKey, message);
        } catch (JsonProcessingException | AmqpException ex) {
            log.error("Failed to publish RabbitMQ event routingKey={}", routingKey, ex);
        }
    }
}
