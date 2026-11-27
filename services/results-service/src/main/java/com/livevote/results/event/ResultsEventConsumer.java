package com.livevote.results.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livevote.results.config.RabbitMqConfig;
import com.livevote.results.config.TraceIdFilter;
import com.livevote.results.dto.PollResultsResponse;
import com.livevote.results.exception.InvalidEventException;
import com.livevote.results.service.ResultsProjectionService;
import com.livevote.results.service.ResultsStreamService;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes versioned JSON events from RabbitMQ, correlates AMQP traceId in MDC, and records metrics.
 */
@Component
public class ResultsEventConsumer {

    private static final int SUPPORTED_EVENT_VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(ResultsEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final ResultsProjectionService projectionService;
    private final ResultsStreamService resultsStreamService;
    private final MeterRegistry meterRegistry;

    public ResultsEventConsumer(
            ObjectMapper objectMapper,
            ResultsProjectionService projectionService,
            ResultsStreamService resultsStreamService,
            MeterRegistry meterRegistry
    ) {
        this.objectMapper = objectMapper;
        this.projectionService = projectionService;
        this.resultsStreamService = resultsStreamService;
        this.meterRegistry = meterRegistry;
    }

    @RabbitListener(queues = RabbitMqConfig.EVENTS_QUEUE, containerFactory = "rabbitListenerContainerFactory")
    public void onMessage(Message message) {
        String traceId = extractTraceId(message);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(TraceIdFilter.MDC_TRACE_ID, traceId)) {
            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            if (routingKey == null) {
                recordConsumedMetric("unknown", "dlq");
                throw new AmqpRejectAndDontRequeueException("Missing routing key on event message");
            }

            try {
                switch (routingKey) {
                    case RabbitMqConfig.ROUTING_KEY_POLL_CREATED -> {
                        PollCreatedEvent event = parse(message.getBody(), PollCreatedEvent.class);
                        validatePollCreated(event);
                        projectionService.handlePollCreated(event);
                        recordConsumedMetric(routingKey, "processed");
                        log.info("Projected PollCreated pollId={} optionsCount={}",
                                event.pollId(), event.options().size());
                    }
                    case RabbitMqConfig.ROUTING_KEY_VOTE_CAST -> {
                        VoteCastEvent event = parse(message.getBody(), VoteCastEvent.class);
                        validateVoteCast(event);
                        Optional<PollResultsResponse> updated = projectionService.handleVoteCast(event);
                        if (updated.isPresent()) {
                            PollResultsResponse response = updated.get();
                            resultsStreamService.publishUpdate(response);
                            recordConsumedMetric(routingKey, "processed");
                            log.info("Projected VoteCast pollId={} voteId={} totalVotes={}",
                                    event.pollId(), event.voteId(), response.totalVotes());
                        } else {
                            recordConsumedMetric(routingKey, "duplicate");
                            log.info("Ignored duplicate VoteCast pollId={} voteId={}",
                                    event.pollId(), event.voteId());
                        }
                    }
                    default -> throw new InvalidEventException("Unsupported routing key: " + routingKey);
                }
            } catch (InvalidEventException ex) {
                recordConsumedMetric(routingKey, "dlq");
                log.warn("Rejecting poison message routingKey={} reason={}", routingKey, ex.getMessage());
                throw new AmqpRejectAndDontRequeueException(ex.getMessage(), ex);
            }
        }
    }

    public static String extractTraceId(Message message) {
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        Object raw = headers.get(TraceIdFilter.HEADER_TRACE_ID);
        if (raw == null) {
            raw = headers.get(TraceIdFilter.MDC_TRACE_ID);
        }
        String candidate = null;
        if (raw instanceof byte[] bytes) {
            candidate = new String(bytes, StandardCharsets.UTF_8);
        } else if (raw != null) {
            candidate = raw.toString();
        }
        return TraceIdFilter.resolveOrGenerateTraceId(candidate);
    }

    private void recordConsumedMetric(String routingKey, String outcome) {
        meterRegistry.counter("livevote.events.consumed.total",
                "routingKey", routingKey,
                "outcome", outcome).increment();
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
