package com.github.guoyyit.graphtrianglestatsjava.exception;

/**
 * Raised when an event fails validation; the whole batch is rejected.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
