package com.github.guoyyit.graphtrianglestatsjava.engine;

import com.github.guoyyit.graphtrianglestatsjava.domain.Edge;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventOperation;
import com.github.guoyyit.graphtrianglestatsjava.domain.GraphSnapshot;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Incrementally maintains triangle counts and clustering coefficients.
 *
 * <p>The engine keeps mutable state and is designed for a single writer.
 * Events are idempotent state transitions: adding an existing edge or removing
 * a missing edge changes nothing, so duplicated or reordered events never
 * corrupt the statistics. Each {@link EdgeEvent} is first normalized into a
 * canonical undirected {@link Edge} before being applied.
 */
public final class TriangleStatsEngine {

    private final Map<String, Set<String>> adjacency = new HashMap<>();
    private final Map<Edge, Integer> edgeTriangles = new HashMap<>();
    private final Map<String, Integer> vertexTriangles = new HashMap<>();
    private final Map<String, Integer> degrees = new HashMap<>();
    private long totalTriangles;

    /**
     * Applies one normalized event. Returns whether the graph state changed;
     * a no-op duplicate event returns {@code false}.
     */
    public boolean apply(EdgeEvent event) {
        Edge edge = Edge.of(event.source(), event.target());
        return event.operation() == EventOperation.ADD_EDGE ? addEdge(edge) : removeEdge(edge);
    }

    private boolean addEdge(Edge edge) {
        if (edgeTriangles.containsKey(edge)) {
            return false;
        }
        Set<String> leftNeighbors = adjacency.computeIfAbsent(edge.smaller(), key -> new HashSet<>());
        Set<String> rightNeighbors = adjacency.computeIfAbsent(edge.larger(), key -> new HashSet<>());
        Set<String> common = new HashSet<>(leftNeighbors);
        common.retainAll(rightNeighbors);

        edgeTriangles.put(edge, common.size());
        vertexTriangles.merge(edge.smaller(), common.size(), Integer::sum);
        vertexTriangles.merge(edge.larger(), common.size(), Integer::sum);
        for (String vertex : common) {
            totalTriangles++;
            vertexTriangles.merge(vertex, 1, Integer::sum);
            edgeTriangles.merge(Edge.of(edge.smaller(), vertex), 1, Integer::sum);
            edgeTriangles.merge(Edge.of(edge.larger(), vertex), 1, Integer::sum);
        }
        leftNeighbors.add(edge.larger());
        rightNeighbors.add(edge.smaller());
        degrees.merge(edge.smaller(), 1, Integer::sum);
        degrees.merge(edge.larger(), 1, Integer::sum);
        vertexTriangles.putIfAbsent(edge.smaller(), 0);
        vertexTriangles.putIfAbsent(edge.larger(), 0);
        return true;
    }

    private boolean removeEdge(Edge edge) {
        if (!edgeTriangles.containsKey(edge)) {
            return false;
        }
        Set<String> leftNeighbors = adjacency.get(edge.smaller());
        Set<String> rightNeighbors = adjacency.get(edge.larger());
        Set<String> common = new HashSet<>(leftNeighbors);
        common.retainAll(rightNeighbors);

        vertexTriangles.merge(edge.smaller(), -common.size(), Integer::sum);
        vertexTriangles.merge(edge.larger(), -common.size(), Integer::sum);
        for (String vertex : common) {
            totalTriangles--;
            vertexTriangles.merge(vertex, -1, Integer::sum);
            edgeTriangles.merge(Edge.of(edge.smaller(), vertex), -1, Integer::sum);
            edgeTriangles.merge(Edge.of(edge.larger(), vertex), -1, Integer::sum);
        }
        edgeTriangles.remove(edge);
        leftNeighbors.remove(edge.larger());
        rightNeighbors.remove(edge.smaller());
        degrees.merge(edge.smaller(), -1, Integer::sum);
        degrees.merge(edge.larger(), -1, Integer::sum);
        return true;
    }

    /**
     * Builds an immutable snapshot of the current state at the given version.
     */
    public GraphSnapshot snapshot(long version) {
        Map<String, Double> clustering = new HashMap<>(degrees.size());
        long wedges = 0;
        for (Map.Entry<String, Integer> entry : degrees.entrySet()) {
            int degree = entry.getValue();
            int triangles = vertexTriangles.getOrDefault(entry.getKey(), 0);
            clustering.put(entry.getKey(), localClustering(triangles, degree));
            wedges += (long) degree * (degree - 1) / 2;
        }
        return new GraphSnapshot(
                version,
                Map.copyOf(vertexTriangles),
                Map.copyOf(edgeTriangles),
                Map.copyOf(degrees),
                totalTriangles,
                wedges,
                Map.copyOf(clustering));
    }

    private static double localClustering(int triangles, int degree) {
        if (degree < 2) {
            return 0.0;
        }
        return (2.0 * triangles) / ((long) degree * (degree - 1));
    }

    public int vertexCount() {
        int active = 0;
        for (int degree : degrees.values()) {
            if (degree > 0) {
                active++;
            }
        }
        return active;
    }

    public int edgeCount() {
        return edgeTriangles.size();
    }

    public Set<String> vertices() {
        return Collections.unmodifiableSet(degrees.keySet());
    }

    public Set<Edge> edges() {
        return Collections.unmodifiableSet(edgeTriangles.keySet());
    }

    public long totalTriangles() {
        return totalTriangles;
    }

    /**
     * Deep-copies this engine so a candidate batch can be simulated without
     * touching the live, query-visible state.
     */
    public TriangleStatsEngine copy() {
        TriangleStatsEngine clone = new TriangleStatsEngine();
        for (Map.Entry<String, Set<String>> entry : adjacency.entrySet()) {
            clone.adjacency.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        clone.edgeTriangles.putAll(edgeTriangles);
        clone.vertexTriangles.putAll(vertexTriangles);
        clone.degrees.putAll(degrees);
        clone.totalTriangles = totalTriangles;
        return clone;
    }

    /**
     * Replaces this engine's state by replaying the given events from empty.
     * Used during crash recovery.
     */
    public void resetAndReplay(Iterable<EdgeEvent> events) {
        adjacency.clear();
        edgeTriangles.clear();
        vertexTriangles.clear();
        degrees.clear();
        totalTriangles = 0;
        for (EdgeEvent event : events) {
            apply(event);
        }
    }
}
