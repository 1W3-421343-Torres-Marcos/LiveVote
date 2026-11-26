package com.livevote.votes.common;

import org.springframework.http.HttpStatus;

/**
 * Base exception for domain and HTTP status errors mapped uniformly.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
