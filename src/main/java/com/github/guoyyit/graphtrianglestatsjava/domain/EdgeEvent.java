package com.github.guoyyit.graphtrianglestatsjava.domain;

/**
 * A single edge add/remove event.
 */
public record EdgeEvent(EventOperation operation, String source, String target) {
}
