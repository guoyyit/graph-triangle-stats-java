package com.github.guoyyit.graphtrianglestatsjava.web;

import com.github.guoyyit.graphtrianglestatsjava.store.CapacityExceededException;
import com.github.guoyyit.graphtrianglestatsjava.store.EventValidationException;
import com.github.guoyyit.graphtrianglestatsjava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EventValidationException.class)
    public ResponseEntity<ErrorResponse> validation(EventValidationException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED", e.getMessage()));
    }

    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<ErrorResponse> capacity(CapacityExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("CAPACITY_EXCEEDED", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> malformed(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("MALFORMED_REQUEST", e.getMessage()));
    }
}
