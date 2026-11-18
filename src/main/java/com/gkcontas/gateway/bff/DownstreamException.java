package com.gkcontas.gateway.bff;

import org.springframework.http.HttpStatus;

/** A required part of an aggregated response could not be obtained. */
public class DownstreamException extends RuntimeException {

    private final HttpStatus status;

    public DownstreamException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
