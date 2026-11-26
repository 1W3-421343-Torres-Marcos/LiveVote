package com.livevote.votes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the LiveVote votes-service microservice.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VotesServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VotesServiceApplication.class, args);
    }
}
