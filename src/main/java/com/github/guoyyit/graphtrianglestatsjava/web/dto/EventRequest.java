package com.github.guoyyit.graphtrianglestatsjava.web.dto;

import com.github.guoyyit.graphtrianglestatsjava.domain.EventOperation;

/**
 * One add/remove edge event as submitted by a client.
 */
public record EventRequest(EventOperation operation, String source, String target) {
}
