package com.github.guoyyit.graphtrianglestatsjava.web;

import com.github.guoyyit.graphtrianglestatsjava.exception.CapacityExceededException;
import com.github.guoyyit.graphtrianglestatsjava.exception.InvalidEventException;
import com.github.guoyyit.graphtrianglestatsjava.exception.UnknownVersionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Maps domain failures to distinct HTTP status codes so callers can tell
 * validation rejection, capacity rejection and missing versions apart.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidEventException.class)
    public ResponseEntity<Map<String, Object>> invalid(InvalidEventException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_EVENT", exception.getMessage());
    }

    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<Map<String, Object>> capacity(CapacityExceededException exception) {
        return error(HttpStatus.INSUFFICIENT_STORAGE, "CAPACITY_EXCEEDED", exception.getMessage());
    }

    @ExceptionHandler(UnknownVersionException.class)
    public ResponseEntity<Map<String, Object>> unknownVersion(UnknownVersionException exception) {
        return error(HttpStatus.NOT_FOUND, "UNKNOWN_VERSION", exception.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "code", code,
                "message", message));
    }
}
