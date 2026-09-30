package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.config.GraphProperties;
import com.github.guoyyit.graphtrianglestatsjava.domain.Edge;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventOperation;
import com.github.guoyyit.graphtrianglestatsjava.domain.GraphSnapshot;
import com.github.guoyyit.graphtrianglestatsjava.service.GraphStatsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IncrementalConsistencyTest {

    @Test
    void incrementalStatsMatchBruteForceRecompute(@TempDir Path walDir) {
        GraphProperties properties = new GraphProperties();
        properties.setWalDir(walDir.toString());
        GraphStatsService service = new GraphStatsService(properties);
        service.recover();

        Set<Edge> currentEdges = new HashSet<>();
        Random random = new Random(42);
        int vertices = 18;

        for (int batch = 1; batch <= 60; batch++) {
            List<EdgeEvent> events = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                int first = random.nextInt(vertices);
                int second = random.nextInt(vertices);
                if (first == second) {
                    continue;
                }
                Edge edge = Edge.of("v" + first, "v" + second);
                boolean add = !currentEdges.contains(edge) || random.nextBoolean();
                events.add(new EdgeEvent(
                        add ? EventOperation.ADD_EDGE : EventOperation.REMOVE_EDGE,
                        edge.smaller(), edge.larger()));
                if (add) {
                    currentEdges.add(edge);
                } else {
                    currentEdges.remove(edge);
                }
            }
            if (events.isEmpty()) {
                continue;
            }
            service.commit(events);
            BruteForceStats expected = BruteForceStats.compute(currentEdges);
            GraphSnapshot actual = service.current();

            assertEquals(expected.totalTriangles, actual.totalTriangles(),
                    "triangle total mismatch at batch " + batch);
            assertEquals(expected.vertexTriangles, actual.vertexTriangles(),
                    "vertex triangles mismatch at batch " + batch);
            assertEquals(expected.edgeTriangles, actual.edgeTriangles(),
                    "edge triangles mismatch at batch " + batch);
            assertEquals(expected.degrees, actual.degrees(),
                    "degrees mismatch at batch " + batch);
        }
    }

    private static final class BruteForceStats {
        private long totalTriangles;
        private Map<String, Integer> vertexTriangles = new HashMap<>();
        private Map<Edge, Integer> edgeTriangles = new HashMap<>();
        private Map<String, Integer> degrees = new HashMap<>();

        private static BruteForceStats compute(Set<Edge> edges) {
            BruteForceStats stats = new BruteForceStats();
            Map<String, Set<String>> adjacency = new HashMap<>();
            for (Edge edge : edges) {
                adjacency.computeIfAbsent(edge.smaller(), key -> new HashSet<>()).add(edge.larger());
                adjacency.computeIfAbsent(edge.larger(), key -> new HashSet<>()).add(edge.smaller());
                stats.edgeTriangles.put(edge, 0);
                stats.degrees.merge(edge.smaller(), 1, Integer::sum);
                stats.degrees.merge(edge.larger(), 1, Integer::sum);
            }
            for (String vertex : adjacency.keySet()) {
                stats.vertexTriangles.put(vertex, 0);
            }
            for (Edge edge : edges) {
                Set<String> left = adjacency.getOrDefault(edge.smaller(), Set.of());
                Set<String> right = adjacency.getOrDefault(edge.larger(), Set.of());
                Set<String> common = new HashSet<>(left);
                common.retainAll(right);
                stats.edgeTriangles.put(edge, common.size());
                for (String vertex : common) {
                    stats.vertexTriangles.merge(vertex, 1, Integer::sum);
                }
            }
            for (int triangles : stats.vertexTriangles.values()) {
                stats.totalTriangles += triangles;
            }
            stats.totalTriangles /= 3;
            return stats;
        }
    }
}
