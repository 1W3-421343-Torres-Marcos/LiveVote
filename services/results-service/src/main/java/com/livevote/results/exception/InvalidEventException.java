package com.livevote.results.exception;

/**
 * Thrown when an incoming AMQP message violates schema or contract rules (poison message -> DLQ).
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
