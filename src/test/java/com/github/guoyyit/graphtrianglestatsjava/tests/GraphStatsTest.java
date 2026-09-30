package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.domain.Edge;
import com.github.guoyyit.graphtrianglestatsjava.stats.GraphStats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphStatsTest {

    @Test
    void singleTriangleIsCounted() {
        GraphStats stats = new GraphStats();
        stats.addEdge(1, 2);
        stats.addEdge(2, 3);
        stats.addEdge(1, 3);
        assertEquals(1, stats.globalTriangles());
        assertEquals(1, stats.nodeTriangles(1));
        assertEquals(1, stats.nodeTriangles(2));
        assertEquals(1, stats.nodeTriangles(3));
        assertEquals(1, stats.edgeTriangles(1, 2));
        assertEquals(1, stats.edgeTriangles(2, 3));
        assertEquals(1, stats.edgeTriangles(1, 3));
        assertEquals(3, stats.wedges());
        assertEquals(3, stats.vertexCount());
        assertEquals(3, stats.edgeCount());
    }

    @Test
    void duplicateAddAndMissingRemoveAreNoOps() {
        GraphStats stats = new GraphStats();
        assertTrue(stats.addEdge(1, 2));
        assertFalse(stats.addEdge(1, 2));
        assertFalse(stats.addEdge(2, 1));
        assertEquals(1, stats.edgeCount());
        assertFalse(stats.removeEdge(2, 3));
        assertEquals(1, stats.edgeCount());
        assertTrue(stats.removeEdge(1, 2));
        assertEquals(0, stats.edgeCount());
        assertEquals(0, stats.vertexCount());
    }

    @Test
    void removalDecrementsTriangleCounts() {
        GraphStats stats = new GraphStats();
        stats.addEdge(1, 2);
        stats.addEdge(2, 3);
        stats.addEdge(1, 3);
        assertTrue(stats.removeEdge(1, 3));
        assertEquals(0, stats.globalTriangles());
        assertEquals(0, stats.nodeTriangles(1));
        assertEquals(0, stats.nodeTriangles(2));
        assertEquals(0, stats.nodeTriangles(3));
        assertEquals(0, stats.edgeTriangles(1, 2));
        assertEquals(1, stats.wedges());
    }

    @Test
    void incrementalMaintenanceMatchesFullRecompute() {
        Random random = new Random(42);
        for (int trial = 0; trial < 20; trial++) {
            GraphStats incremental = new GraphStats();
            Set<Edge> expectedEdges = new HashSet<>();
            int nodeSpace = 6 + random.nextInt(20);
            int operations = 200 + random.nextInt(300);
            for (int i = 0; i < operations; i++) {
                long u = random.nextInt(nodeSpace);
                long v = random.nextInt(nodeSpace);
                if (u == v) {
                    continue;
                }
                Edge edge = Edge.of(u, v);
                if (random.nextBoolean()) {
                    if (incremental.addEdge(u, v)) {
                        expectedEdges.add(edge);
                    }
                } else {
                    if (incremental.removeEdge(u, v)) {
                        expectedEdges.remove(edge);
                    }
                }
            }
            GraphStats recomputed = GraphStats.rebuildFrom(expectedEdges);
            assertEquals(recomputed.globalTriangles(), incremental.globalTriangles());
            assertEquals(recomputed.wedges(), incremental.wedges());
            assertEquals(recomputed.vertexCount(), incremental.vertexCount());
            assertEquals(recomputed.edgeCount(), incremental.edgeCount());
            for (long node = 0; node < nodeSpace; node++) {
                assertEquals(recomputed.nodeTriangles(node), incremental.nodeTriangles(node),
                        "node triangles for " + node);
                assertEquals(recomputed.degree(node), incremental.degree(node), "degree for " + node);
            }
            List<Edge> allEdges = new ArrayList<>(recomputed.edges());
            for (Edge edge : allEdges) {
                assertEquals(recomputed.edgeTriangles(edge.u(), edge.v()),
                        incremental.edgeTriangles(edge.u(), edge.v()),
                        "edge triangles for " + edge);
            }
        }
    }
}
