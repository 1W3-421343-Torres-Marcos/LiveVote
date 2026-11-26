package com.livevote.votes.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a domain rule rejects an otherwise well-formed request (HTTP 422).
 */
public class UnprocessableEntityException extends ApiException {

    public UnprocessableEntityException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
