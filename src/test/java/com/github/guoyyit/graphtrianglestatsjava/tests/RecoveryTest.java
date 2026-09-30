package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.config.GraphProperties;
import com.github.guoyyit.graphtrianglestatsjava.domain.Edge;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventType;
import com.github.guoyyit.graphtrianglestatsjava.service.GraphStatsService;
import com.github.guoyyit.graphtrianglestatsjava.stats.GlobalStats;
import com.github.guoyyit.graphtrianglestatsjava.stats.GraphStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    @TempDir
    Path tempDir;

    private GraphStatsService newService(Path wal) {
        GraphProperties properties = new GraphProperties();
        properties.setWalPath(wal.toString());
        return new GraphStatsService(properties);
    }

    private static EdgeEvent add(long u, long v) {
        return new EdgeEvent(EventType.ADD, u, v);
    }

    private static EdgeEvent remove(long u, long v) {
        return new EdgeEvent(EventType.REMOVE, u, v);
    }

    @Test
    void restartRestoresCommittedState() {
        Path wal = tempDir.resolve("restart.log");
        GlobalStats before;
        try (GraphStatsService service = newService(wal)) {
            service.submitBatch(List.of(add(1, 2), add(2, 3), add(1, 3)));
            service.submitBatch(List.of(add(3, 4)));
            service.submitBatch(List.of(remove(3, 4)));
            before = service.globalStats();
        }
        try (GraphStatsService recovered = newService(wal)) {
            GlobalStats after = recovered.globalStats();
            assertEquals(before.version(), after.version());
            assertEquals(before.triangles(), after.triangles());
            assertEquals(before.wedges(), after.wedges());
            assertEquals(before.vertexCount(), after.vertexCount());
            assertEquals(before.edgeCount(), after.edgeCount());
            assertEquals(1, after.triangles());
            assertEquals(3, after.version());
        }
    }

    @Test
    void crashWithoutCleanShutdownRecoversCommittedBatches() {
        Path wal = tempDir.resolve("crash.log");
        GraphStatsService crashed = newService(wal);
        crashed.submitBatch(List.of(add(1, 2), add(2, 3), add(1, 3)));
        crashed.submitBatch(List.of(add(4, 5)));
        try (GraphStatsService recovered = newService(wal)) {
            assertEquals(2, recovered.currentVersion());
            assertEquals(1, recovered.globalStats().triangles());
            assertEquals(4, recovered.globalStats().edgeCount());
            recovered.submitBatch(List.of(add(4, 6)));
            assertEquals(3, recovered.currentVersion());
        }
    }

    @Test
    void tornTailIsDiscardedAndTruncated() throws IOException {
        Path wal = tempDir.resolve("torn.log");
        long cleanLength;
        try (GraphStatsService service = newService(wal)) {
            service.submitBatch(List.of(add(1, 2), add(2, 3), add(1, 3)));
            cleanLength = Files.size(wal);
        }
        byte[] garbage = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Files.write(wal, garbage, StandardOpenOption.APPEND);
        try (GraphStatsService recovered = newService(wal)) {
            assertEquals(1, recovered.currentVersion());
            assertEquals(1, recovered.globalStats().triangles());
            assertEquals(cleanLength, Files.size(wal));
            recovered.submitBatch(List.of(add(3, 4)));
            assertEquals(2, recovered.currentVersion());
        }
        try (GraphStatsService again = newService(wal)) {
            assertEquals(2, again.currentVersion());
            assertEquals(4, again.globalStats().edgeCount());
        }
    }

    @Test
    void replayMatchesFullRecompute() {
        Path wal = tempDir.resolve("replay.log");
        Random random = new Random(7);
        Set<Edge> expectedEdges = new HashSet<>();
        List<GlobalStats> ignored = new ArrayList<>();
        try (GraphStatsService service = newService(wal)) {
            for (int batch = 0; batch < 30; batch++) {
                List<EdgeEvent> events = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    long u = random.nextInt(15);
                    long v = random.nextInt(15);
                    if (u == v) {
                        continue;
                    }
                    Edge edge = Edge.of(u, v);
                    if (random.nextInt(3) == 0) {
                        events.add(remove(u, v));
                        expectedEdges.remove(edge);
                    } else {
                        events.add(add(u, v));
                        expectedEdges.add(edge);
                    }
                }
                if (!events.isEmpty()) {
                    service.submitBatch(events);
                }
            }
            ignored.add(service.globalStats());
        }
        GraphStats recomputed = GraphStats.rebuildFrom(expectedEdges);
        try (GraphStatsService recovered = newService(wal)) {
            GlobalStats stats = recovered.globalStats();
            assertEquals(recomputed.globalTriangles(), stats.triangles());
            assertEquals(recomputed.wedges(), stats.wedges());
            assertEquals(recomputed.vertexCount(), stats.vertexCount());
            assertEquals(recomputed.edgeCount(), stats.edgeCount());
            for (Edge edge : expectedEdges) {
                assertEquals(recomputed.edgeTriangles(edge.u(), edge.v()),
                        recovered.edgeStats(edge.u(), edge.v()).triangles());
            }
            for (long node = 0; node < 15; node++) {
                assertEquals(recomputed.nodeTriangles(node), recovered.nodeStats(node).triangles());
            }
            assertEquals(ignored.get(0).triangles(), stats.triangles());
            assertTrue(stats.version() > 0);
        }
    }
}
