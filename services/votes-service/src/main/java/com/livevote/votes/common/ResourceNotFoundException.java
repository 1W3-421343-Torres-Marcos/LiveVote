package com.livevote.votes.common;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a requested entity does not exist (HTTP 404).
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
