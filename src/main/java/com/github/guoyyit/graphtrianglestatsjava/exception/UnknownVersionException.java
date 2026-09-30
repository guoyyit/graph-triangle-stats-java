package com.github.guoyyit.graphtrianglestatsjava.exception;

/**
 * Raised when a query asks for a version that is no longer retained.
 */
public class UnknownVersionException extends RuntimeException {

    public UnknownVersionException(String message) {
        super(message);
    }
}
