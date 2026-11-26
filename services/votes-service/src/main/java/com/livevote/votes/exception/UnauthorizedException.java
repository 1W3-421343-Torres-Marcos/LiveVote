package com.livevote.votes.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when authentication fails (HTTP 401).
 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
