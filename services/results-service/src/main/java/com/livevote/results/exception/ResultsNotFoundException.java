package com.livevote.results.exception;

/**
 * Thrown when a poll projection does not exist in postgres-results (HTTP 404).
 */
public class ResultsNotFoundException extends RuntimeException {

    public ResultsNotFoundException(String message) {
        super(message);
    }
}
