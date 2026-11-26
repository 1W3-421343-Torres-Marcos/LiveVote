package com.livevote.votes.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown on duplicate registration or duplicate vote per device (HTTP 409).
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
