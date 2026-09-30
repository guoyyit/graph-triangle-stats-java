package com.github.guoyyit.graphtrianglestatsjava.web.dto;

import java.util.List;

/**
 * Request body for an atomic batch commit.
 */
public record BatchRequest(List<EventRequest> events) {
}
