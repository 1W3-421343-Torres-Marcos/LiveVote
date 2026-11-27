package com.livevote.results.config;

import com.livevote.results.event.ResultsEventConsumer;
import com.livevote.results.exception.PollProjectionPendingException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * Declares RabbitMQ topology (main queue, TTL retry queue, DLQ) and error-handling policy.
 */
@Configuration
public class RabbitMqConfig {

    public static final String EVENTS_EXCHANGE = "livevote.events";
    public static final String RETRY_EXCHANGE = "livevote.retry";
    public static final String DLX_EXCHANGE = "livevote.dlx";

    public static final String EVENTS_QUEUE = "results.events.queue";
    public static final String RETRY_QUEUE = "results.events.retry.queue";
    public static final String DLQ_QUEUE = "results.events.dlq";

    public static final String ROUTING_KEY_POLL_CREATED = "poll.created";
    public static final String ROUTING_KEY_VOTE_CAST = "vote.cast";

    private static final String HEADER_PENDING_RETRIES = "x-pending-retries";
    private static final String HEADER_TRANSIENT_RETRIES = "x-transient-retries";
    private static final Logger log = LoggerFactory.getLogger(RabbitMqConfig.class);

    @Bean
    public TopicExchange livevoteEventsExchange() {
        return ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange livevoteRetryExchange() {
        return ExchangeBuilder.topicExchange(RETRY_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange livevoteDlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue resultsEventsQueue() {
        return QueueBuilder.durable(EVENTS_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_QUEUE)
                .build();
    }

    @Bean
    public Queue resultsRetryQueue(MessagingProperties properties) {
        return QueueBuilder.durable(RETRY_QUEUE)
                .ttl(Math.toIntExact(properties.getRetryDelayMs()))
                // Preserve original routing key when TTL expires and dead-letters back to livevote.events.
                .deadLetterExchange(EVENTS_EXCHANGE)
                .build();
    }

    @Bean
    public Queue resultsDlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding pollCreatedBinding(Queue resultsEventsQueue, TopicExchange livevoteEventsExchange) {
        return BindingBuilder.bind(resultsEventsQueue).to(livevoteEventsExchange).with(ROUTING_KEY_POLL_CREATED);
    }

    @Bean
    public Binding voteCastBinding(Queue resultsEventsQueue, TopicExchange livevoteEventsExchange) {
        return BindingBuilder.bind(resultsEventsQueue).to(livevoteEventsExchange).with(ROUTING_KEY_VOTE_CAST);
    }

    @Bean
    public Binding retryQueueBinding(Queue resultsRetryQueue, TopicExchange livevoteRetryExchange) {
        return BindingBuilder.bind(resultsRetryQueue).to(livevoteRetryExchange).with("#");
    }

    @Bean
    public Binding dlqBinding(Queue resultsDlqQueue, DirectExchange livevoteDlxExchange) {
        return BindingBuilder.bind(resultsDlqQueue).to(livevoteDlxExchange).with(DLQ_QUEUE);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate,
            MessagingProperties properties,
            MeterRegistry meterRegistry
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                properties.getLocalRetryMaxAttempts(),
                Map.of(AmqpRejectAndDontRequeueException.class, false),
                true,
                true
        );

        MessageRecoverer recoverer = createRecoverer(rabbitTemplate, properties, meterRegistry);

        RetryOperationsInterceptor retryInterceptor = RetryInterceptorBuilder.stateless()
                .retryPolicy(retryPolicy)
                .backOffOptions(properties.getLocalRetryInitialMs(), 2.0, 2000L)
                .recoverer(recoverer)
                .build();

        factory.setAdviceChain(retryInterceptor);
        return factory;
    }

    private MessageRecoverer createRecoverer(
            RabbitTemplate rabbitTemplate,
            MessagingProperties properties,
            MeterRegistry meterRegistry
    ) {
        return (message, cause) -> {
            String traceId = ResultsEventConsumer.extractTraceId(message);
            try (MDC.MDCCloseable ignored = MDC.putCloseable(TraceIdFilter.MDC_TRACE_ID, traceId)) {
                if (hasCause(cause, AmqpRejectAndDontRequeueException.class)) {
                    throw (cause instanceof AmqpRejectAndDontRequeueException direct)
                            ? direct
                            : new AmqpRejectAndDontRequeueException("Poison message rejected to DLQ", cause);
                }

                String routingKey = message.getMessageProperties().getReceivedRoutingKey();
                if (routingKey == null || routingKey.isBlank()) {
                    meterRegistry.counter("livevote.events.consumed.total",
                            "routingKey", "unknown",
                            "outcome", "dlq").increment();
                    throw new AmqpRejectAndDontRequeueException("Missing routing key on failed message", cause);
                }

                if (hasCause(cause, PollProjectionPendingException.class)) {
                    int pendingAttempts = getHeaderInt(message, HEADER_PENDING_RETRIES);
                    if (pendingAttempts >= properties.getMaxPendingRetries()) {
                        meterRegistry.counter("livevote.events.consumed.total",
                                "routingKey", routingKey,
                                "outcome", "dlq").increment();
                        log.warn("Sending event to DLQ after {} pending projection retries, routingKey={}",
                                pendingAttempts, routingKey);
                        throw new AmqpRejectAndDontRequeueException("Exceeded max pending retries", cause);
                    }
                    int nextAttempt = pendingAttempts + 1;
                    Message retryMessage = copyWithHeader(message, HEADER_PENDING_RETRIES, nextAttempt);
                    rabbitTemplate.send(RETRY_EXCHANGE, routingKey, retryMessage);
                    meterRegistry.counter("livevote.events.consumed.total",
                            "routingKey", routingKey,
                            "outcome", "retry").increment();
                    log.info("Scheduled delayed retry {} for out-of-order event routingKey={}", nextAttempt, routingKey);
                    return;
                }

                int transientAttempts = getHeaderInt(message, HEADER_TRANSIENT_RETRIES) + 1;
                Message retryMessage = copyWithHeader(message, HEADER_TRANSIENT_RETRIES, transientAttempts);
                rabbitTemplate.send(RETRY_EXCHANGE, routingKey, retryMessage);
                meterRegistry.counter("livevote.events.consumed.total",
                        "routingKey", routingKey,
                        "outcome", "retry").increment();
                log.warn("Transient failure processing event routingKey={}; scheduled retry attempt={}",
                        routingKey, transientAttempts, cause);
            }
        };
    }

    private Message copyWithHeader(Message source, String headerName, int value) {
        return MessageBuilder.fromMessage(source).setHeader(headerName, value).build();
    }

    private int getHeaderInt(Message message, String headerName) {
        Object value = message.getMessageProperties().getHeaders().get(headerName);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> targetType) {
        Throwable current = throwable;
        while (current != null) {
            if (targetType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
