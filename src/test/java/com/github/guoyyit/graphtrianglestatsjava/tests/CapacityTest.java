package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.config.GraphProperties;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventType;
import com.github.guoyyit.graphtrianglestatsjava.service.GraphStatsService;
import com.github.guoyyit.graphtrianglestatsjava.store.CapacityExceededException;
import com.github.guoyyit.graphtrianglestatsjava.store.EventValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityTest {

    @TempDir
    Path tempDir;

    private GraphStatsService newService(String name, int maxVertices, long maxEdges) {
        GraphProperties properties = new GraphProperties();
        properties.setWalPath(tempDir.resolve(name).toString());
        properties.setMaxVertices(maxVertices);
        properties.setMaxEdges(maxEdges);
        return new GraphStatsService(properties);
    }

    private static EdgeEvent add(long u, long v) {
        return new EdgeEvent(EventType.ADD, u, v);
    }

    private static EdgeEvent remove(long u, long v) {
        return new EdgeEvent(EventType.REMOVE, u, v);
    }

    @Test
    void edgeCapacityRejectsWholeBatchWithoutPartialEffects() {
        try (GraphStatsService service = newService("edges.log", 100, 2)) {
            service.submitBatch(List.of(add(1, 2), add(2, 3)));
            long version = service.currentVersion();
            assertThrows(CapacityExceededException.class,
                    () -> service.submitBatch(List.of(add(3, 4), add(4, 5))));
            assertEquals(version, service.currentVersion());
            assertEquals(2, service.globalStats().edgeCount());
            assertFalse(service.edgeStats(3, 4).exists());
            assertFalse(service.edgeStats(4, 5).exists());
        }
    }

    @Test
    void vertexCapacityRejectsBatchIntroducingTooManyVertices() {
        try (GraphStatsService service = newService("vertices.log", 3, 100)) {
            service.submitBatch(List.of(add(1, 2), add(2, 3)));
            assertThrows(CapacityExceededException.class,
                    () -> service.submitBatch(List.of(add(3, 4))));
            assertEquals(3, service.globalStats().vertexCount());
        }
    }

    @Test
    void capacityIsCheckedAgainstPostBatchState() {
        try (GraphStatsService service = newService("swap.log", 100, 2)) {
            service.submitBatch(List.of(add(1, 2), add(2, 3)));
            service.submitBatch(List.of(remove(1, 2), add(3, 4)));
            assertEquals(2, service.globalStats().edgeCount());
            assertTrue(service.edgeStats(3, 4).exists());
            assertFalse(service.edgeStats(1, 2).exists());
        }
    }

    @Test
    void capacityRejectionIsDistinguishableFromValidationFailure() {
        try (GraphStatsService service = newService("distinct.log", 100, 1)) {
            assertThrows(EventValidationException.class,
                    () -> service.submitBatch(List.of(add(7, 7))));
            assertThrows(CapacityExceededException.class,
                    () -> service.submitBatch(List.of(add(1, 2), add(3, 4))));
            assertEquals(0, service.currentVersion());
            assertEquals(0, service.globalStats().edgeCount());
        }
    }

    @Test
    void serviceRecoversWithinCapacityAfterRestart() {
        Path wal = tempDir.resolve("recover.log");
        try (GraphStatsService service = newService("recover.log", 100, 3)) {
            service.submitBatch(List.of(add(1, 2), add(2, 3), add(1, 3)));
        }
        try (GraphStatsService recovered = newService("recover.log", 100, 3)) {
            assertEquals(3, recovered.globalStats().edgeCount());
            assertThrows(CapacityExceededException.class,
                    () -> recovered.submitBatch(List.of(add(3, 4))));
        }
    }
}
