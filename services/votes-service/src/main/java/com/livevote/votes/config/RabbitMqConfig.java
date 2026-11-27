package com.livevote.votes.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the durable RabbitMQ topic exchange and warms up broker connectivity at startup.
 */
@Configuration
public class RabbitMqConfig {

    public static final String EVENTS_EXCHANGE = "livevote.events";
    public static final String ROUTING_KEY_POLL_CREATED = "poll.created";
    public static final String ROUTING_KEY_VOTE_CAST = "vote.cast";
    public static final String ROUTING_KEY_POLL_CLOSED = "poll.closed";

    private static final Logger log = LoggerFactory.getLogger(RabbitMqConfig.class);

    @Bean
    public TopicExchange livevoteEventsExchange() {
        return ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    public ApplicationListener<ApplicationReadyEvent> rabbitWarmupListener(ObjectProvider<AmqpAdmin> amqpAdminProvider) {
        return event -> {
            AmqpAdmin amqpAdmin = amqpAdminProvider.getIfAvailable();
            if (amqpAdmin == null) {
                return;
            }
            try {
                amqpAdmin.initialize();
                log.info("RabbitMQ exchange declaration warmup completed exchange={}", EVENTS_EXCHANGE);
            } catch (AmqpException ex) {
                log.warn("RabbitMQ warmup deferred until first publish: {}", ex.getMessage());
            }
        };
    }
}
