package com.github.guoyyit.graphtrianglestatsjava.web.dto;

public record SubmitBatchResponse(long version, int appliedEvents, int ignoredEvents) {
}
