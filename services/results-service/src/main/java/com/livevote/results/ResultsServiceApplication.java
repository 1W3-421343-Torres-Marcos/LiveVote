package com.livevote.results;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the LiveVote results-service microservice.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ResultsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResultsServiceApplication.class, args);
    }
}
