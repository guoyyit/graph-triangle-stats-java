package com.github.guoyyit.graphtrianglestatsjava.store;

public record BatchResult(long version, int appliedEvents, int ignoredEvents) {
}
