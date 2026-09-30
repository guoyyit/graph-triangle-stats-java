package com.github.guoyyit.graphtrianglestatsjava.exception;

/**
 * Raised when a batch would exceed configured graph capacity bounds.
 */
public class CapacityExceededException extends RuntimeException {

    public CapacityExceededException(String message) {
        super(message);
    }
}
