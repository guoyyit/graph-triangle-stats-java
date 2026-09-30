package com.github.guoyyit.graphtrianglestatsjava.wal;

import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;

import java.util.List;

public record CommittedBatch(long version, List<EdgeEvent> events) {
}
