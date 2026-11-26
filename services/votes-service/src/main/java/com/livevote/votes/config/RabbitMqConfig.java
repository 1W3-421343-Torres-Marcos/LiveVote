package com.livevote.votes.config;

import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the durable RabbitMQ topic exchange and routing keys published by votes-service.
 */
@Configuration
public class RabbitMqConfig {

    public static final String EVENTS_EXCHANGE = "livevote.events";
    public static final String ROUTING_KEY_POLL_CREATED = "poll.created";
    public static final String ROUTING_KEY_VOTE_CAST = "vote.cast";

    @Bean
    public TopicExchange livevoteEventsExchange() {
        return ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
    }
}
