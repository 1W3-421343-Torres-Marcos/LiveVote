package com.livevote.results.exception;

/**
 * Thrown when a VoteCast event arrives before its corresponding PollCreated projection exists.
 */
public class PollProjectionPendingException extends RuntimeException {

    public PollProjectionPendingException(String message) {
        super(message);
    }
}
