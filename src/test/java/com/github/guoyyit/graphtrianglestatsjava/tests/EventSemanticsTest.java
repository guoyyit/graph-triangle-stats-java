package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.config.GraphProperties;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventType;
import com.github.guoyyit.graphtrianglestatsjava.service.GraphStatsService;
import com.github.guoyyit.graphtrianglestatsjava.stats.GlobalStats;
import com.github.guoyyit.graphtrianglestatsjava.store.BatchResult;
import com.github.guoyyit.graphtrianglestatsjava.store.EventValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSemanticsTest {

    @TempDir
    Path tempDir;

    private GraphStatsService newService(String name) {
        GraphProperties properties = new GraphProperties();
        properties.setWalPath(tempDir.resolve(name).toString());
        return new GraphStatsService(properties);
    }

    private static EdgeEvent add(long u, long v) {
        return new EdgeEvent(EventType.ADD, u, v);
    }

    private static EdgeEvent remove(long u, long v) {
        return new EdgeEvent(EventType.REMOVE, u, v);
    }

    @Test
    void duplicateEventsDoNotChangeStatistics() {
        try (GraphStatsService service = newService("dup.log")) {
            List<EdgeEvent> batch = List.of(add(1, 2), add(2, 3), add(1, 3));
            service.submitBatch(batch);
            GlobalStats before = service.globalStats();
            BatchResult second = service.submitBatch(batch);
            assertEquals(0, second.appliedEvents());
            assertEquals(3, second.ignoredEvents());
            GlobalStats after = service.globalStats();
            assertEquals(before.triangles(), after.triangles());
            assertEquals(before.edgeCount(), after.edgeCount());
            assertEquals(before.wedges(), after.wedges());
            assertEquals(1, before.triangles());
        }
    }

    @Test
    void eventsApplyInArrivalOrder() {
        try (GraphStatsService service = newService("order.log")) {
            service.submitBatch(List.of(remove(1, 2)));
            assertFalse(service.edgeStats(1, 2).exists());
            service.submitBatch(List.of(add(1, 2)));
            assertTrue(service.edgeStats(1, 2).exists());
            service.submitBatch(List.of(remove(1, 2), add(1, 2)));
            assertTrue(service.edgeStats(1, 2).exists());
            service.submitBatch(List.of(add(1, 2), remove(1, 2)));
            assertFalse(service.edgeStats(1, 2).exists());
        }
    }

    @Test
    void invalidEventRejectsWholeBatchAtomically() {
        try (GraphStatsService service = newService("atomic.log")) {
            service.submitBatch(List.of(add(1, 2)));
            long versionBefore = service.currentVersion();
            List<EdgeEvent> batch = List.of(add(2, 3), add(3, 4), add(5, 5));
            assertThrows(EventValidationException.class, () -> service.submitBatch(batch));
            assertEquals(versionBefore, service.currentVersion());
            assertFalse(service.edgeStats(2, 3).exists());
            assertFalse(service.edgeStats(3, 4).exists());
            assertEquals(1, service.globalStats().edgeCount());
        }
    }

    @Test
    void emptyBatchIsRejected() {
        try (GraphStatsService service = newService("empty.log")) {
            assertThrows(EventValidationException.class, () -> service.submitBatch(List.of()));
            assertEquals(0, service.currentVersion());
        }
    }

    @Test
    void versionsIncreaseMonotonicallyPerCommittedBatch() {
        try (GraphStatsService service = newService("version.log")) {
            assertEquals(0, service.currentVersion());
            BatchResult first = service.submitBatch(List.of(add(1, 2)));
            BatchResult second = service.submitBatch(List.of(add(2, 3)));
            assertEquals(1, first.version());
            assertEquals(2, second.version());
            assertEquals(2, service.currentVersion());
            assertEquals(2, service.globalStats().version());
        }
    }

    @Test
    void batchingDoesNotAffectFinalStatistics() {
        List<EdgeEvent> sequence = new ArrayList<>();
        sequence.add(add(1, 2));
        sequence.add(add(2, 3));
        sequence.add(add(1, 3));
        sequence.add(add(3, 4));
        sequence.add(add(1, 4));
        sequence.add(remove(2, 3));
        sequence.add(add(1, 2));
        sequence.add(remove(1, 2));
        sequence.add(add(4, 5));
        try (GraphStatsService oneBatch = newService("one.log");
             GraphStatsService manyBatches = newService("many.log")) {
            oneBatch.submitBatch(sequence);
            for (EdgeEvent event : sequence) {
                manyBatches.submitBatch(List.of(event));
            }
            manyBatches.submitBatch(sequence);
            GlobalStats a = oneBatch.globalStats();
            GlobalStats b = manyBatches.globalStats();
            assertEquals(a.triangles(), b.triangles());
            assertEquals(a.wedges(), b.wedges());
            assertEquals(a.vertexCount(), b.vertexCount());
            assertEquals(a.edgeCount(), b.edgeCount());
            assertEquals(a.globalClusteringCoefficient(), b.globalClusteringCoefficient());
            assertEquals(a.averageLocalClusteringCoefficient(), b.averageLocalClusteringCoefficient());
            for (long node = 1; node <= 5; node++) {
                assertEquals(oneBatch.nodeStats(node), manyBatches.nodeStats(node), "node " + node);
            }
        }
    }
}
