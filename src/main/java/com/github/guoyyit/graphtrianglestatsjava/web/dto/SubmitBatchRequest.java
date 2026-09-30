package com.github.guoyyit.graphtrianglestatsjava.web.dto;

import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;

import java.util.List;

public record SubmitBatchRequest(List<EdgeEvent> events) {
}
