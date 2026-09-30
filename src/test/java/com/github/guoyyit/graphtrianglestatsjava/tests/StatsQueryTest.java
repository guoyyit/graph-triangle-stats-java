package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.config.GraphProperties;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventType;
import com.github.guoyyit.graphtrianglestatsjava.service.GraphStatsService;
import com.github.guoyyit.graphtrianglestatsjava.stats.EdgeStats;
import com.github.guoyyit.graphtrianglestatsjava.stats.GlobalStats;
import com.github.guoyyit.graphtrianglestatsjava.stats.NodeStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StatsQueryTest {

    @TempDir
    Path tempDir;

    private GraphStatsService newService() {
        GraphProperties properties = new GraphProperties();
        properties.setWalPath(tempDir.resolve("stats.log").toString());
        return new GraphStatsService(properties);
    }

    private static EdgeEvent add(long u, long v) {
        return new EdgeEvent(EventType.ADD, u, v);
    }

    @Test
    void localAndGlobalClusteringCoefficientsOnKnownGraph() {
        try (GraphStatsService service = newService()) {
            service.submitBatch(List.of(
                    add(1, 2), add(2, 3), add(1, 3),
                    add(3, 4), add(4, 5), add(3, 5)));

            NodeStats node1 = service.nodeStats(1);
            assertEquals(2, node1.degree());
            assertEquals(1, node1.triangles());
            assertEquals(1.0, node1.localClusteringCoefficient(), 1e-9);

            NodeStats node3 = service.nodeStats(3);
            assertEquals(4, node3.degree());
            assertEquals(2, node3.triangles());
            assertEquals(2.0 / 6.0, node3.localClusteringCoefficient(), 1e-9);

            NodeStats absent = service.nodeStats(99);
            assertEquals(0, absent.degree());
            assertEquals(0, absent.triangles());
            assertEquals(0.0, absent.localClusteringCoefficient(), 1e-9);

            EdgeStats edge34 = service.edgeStats(3, 4);
            assertEquals(1, edge34.triangles());
            EdgeStats missing = service.edgeStats(1, 5);
            assertFalse(missing.exists());
            assertEquals(0, missing.triangles());

            GlobalStats global = service.globalStats();
            assertEquals(5, global.vertexCount());
            assertEquals(6, global.edgeCount());
            assertEquals(2, global.triangles());
            assertEquals(10, global.wedges());
            assertEquals(6.0 / 10.0, global.globalClusteringCoefficient(), 1e-9);
            double expectedAverage = (1.0 + 1.0 + 2.0 / 6.0 + 1.0 + 1.0) / 5.0;
            assertEquals(expectedAverage, global.averageLocalClusteringCoefficient(), 1e-9);
        }
    }

    @Test
    void coefficientsTrackEdgeRemovals() {
        try (GraphStatsService service = newService()) {
            service.submitBatch(List.of(add(1, 2), add(2, 3), add(1, 3)));
            assertEquals(1.0, service.globalStats().globalClusteringCoefficient(), 1e-9);
            service.submitBatch(List.of(new EdgeEvent(EventType.REMOVE, 1, 3)));
            GlobalStats global = service.globalStats();
            assertEquals(0, global.triangles());
            assertEquals(1, global.wedges());
            assertEquals(0.0, global.globalClusteringCoefficient(), 1e-9);
            assertEquals(0.0, service.nodeStats(1).localClusteringCoefficient(), 1e-9);
        }
    }
}
