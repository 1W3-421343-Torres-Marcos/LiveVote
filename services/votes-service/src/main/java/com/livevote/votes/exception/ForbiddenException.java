package com.livevote.votes.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a user attempts an action outside their ownership (HTTP 403).
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
