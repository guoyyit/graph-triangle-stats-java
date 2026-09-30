package com.github.guoyyit.graphtrianglestatsjava.stats;

import com.github.guoyyit.graphtrianglestatsjava.domain.Edge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable in-memory graph statistics. Not thread-safe; callers must
 * provide external synchronization.
 *
 * <p>Maintains per-node and per-edge triangle counts, the global triangle
 * count and the number of wedges (connected triples) incrementally as edges
 * are added and removed. Adding an existing edge or removing a missing edge
 * is a no-op, which makes re-applying an event idempotent.
 */
public class GraphStats {

    private final Map<Long, Set<Long>> adjacency = new HashMap<>();
    private final Map<Long, Long> nodeTriangles = new HashMap<>();
    private final Map<Edge, Long> edgeTriangles = new HashMap<>();
    private long triangles;
    private long wedges;
    private long edgeCount;

    public boolean addEdge(long u, long v) {
        if (u == v) {
            throw new IllegalArgumentException("self-loops are not allowed: " + u);
        }
        Set<Long> uNeighbors = adjacency.get(u);
        if (uNeighbors != null && uNeighbors.contains(v)) {
            return false;
        }
        List<Long> common = commonNeighbors(u, v);
        long count = common.size();
        for (long w : common) {
            mergeCount(nodeTriangles, w, 1);
            mergeCount(edgeTriangles, Edge.of(u, w), 1);
            mergeCount(edgeTriangles, Edge.of(v, w), 1);
        }
        triangles += count;
        mergeCount(nodeTriangles, u, count);
        mergeCount(nodeTriangles, v, count);
        edgeTriangles.put(Edge.of(u, v), count);
        wedges += degree(u) + degree(v);
        adjacency.computeIfAbsent(u, k -> new HashSet<>()).add(v);
        adjacency.computeIfAbsent(v, k -> new HashSet<>()).add(u);
        edgeCount++;
        return true;
    }

    public boolean removeEdge(long u, long v) {
        Set<Long> uNeighbors = adjacency.get(u);
        if (uNeighbors == null || !uNeighbors.contains(v)) {
            return false;
        }
        List<Long> common = commonNeighbors(u, v);
        long count = common.size();
        for (long w : common) {
            mergeCount(nodeTriangles, w, -1);
            mergeCount(edgeTriangles, Edge.of(u, w), -1);
            mergeCount(edgeTriangles, Edge.of(v, w), -1);
        }
        triangles -= count;
        mergeCount(nodeTriangles, u, -count);
        mergeCount(nodeTriangles, v, -count);
        edgeTriangles.remove(Edge.of(u, v));
        wedges -= (degree(u) - 1L) + (degree(v) - 1L);
        uNeighbors.remove(v);
        if (uNeighbors.isEmpty()) {
            adjacency.remove(u);
        }
        Set<Long> vNeighbors = adjacency.get(v);
        vNeighbors.remove(u);
        if (vNeighbors.isEmpty()) {
            adjacency.remove(v);
        }
        edgeCount--;
        return true;
    }

    public boolean containsEdge(long u, long v) {
        Set<Long> uNeighbors = adjacency.get(u);
        return uNeighbors != null && uNeighbors.contains(v);
    }

    public int degree(long node) {
        Set<Long> neighbors = adjacency.get(node);
        return neighbors == null ? 0 : neighbors.size();
    }

    public long nodeTriangles(long node) {
        return nodeTriangles.getOrDefault(node, 0L);
    }

    public long edgeTriangles(long u, long v) {
        return edgeTriangles.getOrDefault(Edge.of(u, v), 0L);
    }

    public long globalTriangles() {
        return triangles;
    }

    public long wedges() {
        return wedges;
    }

    public int vertexCount() {
        return adjacency.size();
    }

    public long edgeCount() {
        return edgeCount;
    }

    public Set<Long> vertices() {
        return Set.copyOf(adjacency.keySet());
    }

    public List<Edge> edges() {
        List<Edge> edges = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> entry : adjacency.entrySet()) {
            long u = entry.getKey();
            for (long v : entry.getValue()) {
                if (u < v) {
                    edges.add(new Edge(u, v));
                }
            }
        }
        return edges;
    }

    public static GraphStats rebuildFrom(Collection<Edge> edges) {
        GraphStats stats = new GraphStats();
        for (Edge edge : edges) {
            stats.addEdge(edge.u(), edge.v());
        }
        return stats;
    }

    private List<Long> commonNeighbors(long u, long v) {
        Set<Long> uNeighbors = adjacency.getOrDefault(u, Set.of());
        Set<Long> vNeighbors = adjacency.getOrDefault(v, Set.of());
        Set<Long> smaller = uNeighbors.size() <= vNeighbors.size() ? uNeighbors : vNeighbors;
        Set<Long> larger = smaller == uNeighbors ? vNeighbors : uNeighbors;
        List<Long> common = new ArrayList<>();
        for (long w : smaller) {
            if (larger.contains(w)) {
                common.add(w);
            }
        }
        return common;
    }

    private static <K> void mergeCount(Map<K, Long> counts, K key, long delta) {
        if (delta == 0) {
            return;
        }
        long updated = counts.getOrDefault(key, 0L) + delta;
        if (updated == 0) {
            counts.remove(key);
        } else {
            counts.put(key, updated);
        }
    }
}
