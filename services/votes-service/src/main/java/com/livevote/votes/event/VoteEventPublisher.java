package com.livevote.votes.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livevote.votes.config.RabbitMqConfig;
import com.livevote.votes.config.TraceIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes JSON domain events to RabbitMQ after DB commit, propagating traceId and recording publish metrics.
 */
@Component
public class VoteEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(VoteEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public VoteEventPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPollCreated(PollCreatedEvent event) {
        publish(RabbitMqConfig.ROUTING_KEY_POLL_CREATED, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVoteCast(VoteCastEvent event) {
        publish(RabbitMqConfig.ROUTING_KEY_VOTE_CAST, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPollClosed(PollClosedEvent event) {
        publish(RabbitMqConfig.ROUTING_KEY_POLL_CLOSED, event);
    }

    private void publish(String routingKey, Object event) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(event);
            MessageBuilderSupport<Message> builder = MessageBuilder.withBody(payload)
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT);

            String traceId = MDC.get(TraceIdFilter.MDC_TRACE_ID);
            if (traceId != null && !traceId.isBlank()) {
                builder.setHeader(TraceIdFilter.HEADER_TRACE_ID, traceId);
                builder.setHeader(TraceIdFilter.MDC_TRACE_ID, traceId);
            }

            Message message = builder.build();
            rabbitTemplate.send(RabbitMqConfig.EVENTS_EXCHANGE, routingKey, message);
            meterRegistry.counter("livevote.events.published.total",
                    "routingKey", routingKey,
                    "outcome", "success").increment();
            log.info("Published RabbitMQ event routingKey={}", routingKey);
        } catch (JsonProcessingException | AmqpException ex) {
            meterRegistry.counter("livevote.events.published.total",
                    "routingKey", routingKey,
                    "outcome", "error").increment();
            log.error("Failed to publish RabbitMQ event routingKey={}", routingKey, ex);
        }
    }
}
