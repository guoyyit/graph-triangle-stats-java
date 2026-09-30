package com.github.guoyyit.graphtrianglestatsjava.store;

import com.github.guoyyit.graphtrianglestatsjava.domain.Edge;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventType;
import com.github.guoyyit.graphtrianglestatsjava.stats.EdgeStats;
import com.github.guoyyit.graphtrianglestatsjava.stats.GlobalStats;
import com.github.guoyyit.graphtrianglestatsjava.stats.GraphStats;
import com.github.guoyyit.graphtrianglestatsjava.stats.NodeStats;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds the authoritative in-memory state plus the committed version.
 * Not thread-safe; callers must provide external synchronization.
 *
 * <p>Events are applied in arrival order with state-based idempotent
 * semantics: an ADD for an existing edge and a REMOVE for a missing edge
 * are no-ops, so re-applying the same event never changes the statistics.
 */
public class GraphStatsStore {

    private final GraphStats stats = new GraphStats();
    private final int maxVertices;
    private final long maxEdges;
    private long version;

    public GraphStatsStore(int maxVertices, long maxEdges) {
        this.maxVertices = maxVertices;
        this.maxEdges = maxEdges;
    }

    public long currentVersion() {
        return version;
    }

    /**
     * Validates a batch without mutating any state. Capacity is checked
     * against the exact state that would result from applying the whole
     * batch, so acceptance does not depend on how events are grouped.
     */
    public void validateBatch(List<EdgeEvent> events) {
        if (events == null || events.isEmpty()) {
            throw new EventValidationException("batch must contain at least one event");
        }
        for (EdgeEvent event : events) {
            if (event == null || event.type() == null) {
                throw new EventValidationException("event and event type must not be null");
            }
            if (event.u() < 0 || event.v() < 0) {
                throw new EventValidationException(
                        "node ids must be non-negative: (" + event.u() + ", " + event.v() + ")");
            }
            if (event.u() == event.v()) {
                throw new EventValidationException("self-loops are not allowed: " + event.u());
            }
        }
        Set<Edge> added = new HashSet<>();
        Set<Edge> removed = new HashSet<>();
        for (EdgeEvent event : events) {
            Edge edge = Edge.of(event.u(), event.v());
            boolean presentNow = stats.containsEdge(edge.u(), edge.v());
            boolean present = presentNow ? !removed.contains(edge) : added.contains(edge);
            if (event.type() == EventType.ADD && !present) {
                if (presentNow) {
                    removed.remove(edge);
                } else {
                    added.add(edge);
                }
            } else if (event.type() == EventType.REMOVE && present) {
                if (presentNow) {
                    removed.add(edge);
                } else {
                    added.remove(edge);
                }
            }
        }
        long finalEdges = stats.edgeCount() + added.size() - removed.size();
        if (finalEdges > maxEdges) {
            throw new CapacityExceededException(
                    "edge capacity exceeded: limit=" + maxEdges + ", required=" + finalEdges);
        }
        Map<Long, Long> removedDegree = new HashMap<>();
        for (Edge edge : removed) {
            removedDegree.merge(edge.u(), 1L, Long::sum);
            removedDegree.merge(edge.v(), 1L, Long::sum);
        }
        Set<Long> addedEndpoints = new HashSet<>();
        for (Edge edge : added) {
            addedEndpoints.add(edge.u());
            addedEndpoints.add(edge.v());
        }
        long finalVertices = stats.vertexCount();
        for (Map.Entry<Long, Long> entry : removedDegree.entrySet()) {
            long node = entry.getKey();
            if (entry.getValue() == stats.degree(node) && !addedEndpoints.contains(node)) {
                finalVertices--;
            }
        }
        for (long node : addedEndpoints) {
            if (stats.degree(node) == 0) {
                finalVertices++;
            }
        }
        if (finalVertices > maxVertices) {
            throw new CapacityExceededException(
                    "vertex capacity exceeded: limit=" + maxVertices + ", required=" + finalVertices);
        }
    }

    public BatchResult applyCommittedBatch(long version, List<EdgeEvent> events) {
        int applied = 0;
        int ignored = 0;
        for (EdgeEvent event : events) {
            boolean changed = event.type() == EventType.ADD
                    ? stats.addEdge(event.u(), event.v())
                    : stats.removeEdge(event.u(), event.v());
            if (changed) {
                applied++;
            } else {
                ignored++;
            }
        }
        this.version = version;
        return new BatchResult(version, applied, ignored);
    }

    public NodeStats nodeStats(long node) {
        int degree = stats.degree(node);
        long triangles = stats.nodeTriangles(node);
        return new NodeStats(node, degree, triangles, localClusteringCoefficient(degree, triangles));
    }

    public EdgeStats edgeStats(long u, long v) {
        return new EdgeStats(u, v, stats.containsEdge(u, v), stats.edgeTriangles(u, v));
    }

    public GlobalStats globalStats() {
        long wedges = stats.wedges();
        long triangles = stats.globalTriangles();
        double transitivity = wedges == 0 ? 0.0 : 3.0 * triangles / wedges;
        double localSum = 0.0;
        for (long node : stats.vertices()) {
            localSum += localClusteringCoefficient(stats.degree(node), stats.nodeTriangles(node));
        }
        double averageLocal = stats.vertexCount() == 0 ? 0.0 : localSum / stats.vertexCount();
        return new GlobalStats(version, stats.vertexCount(), stats.edgeCount(), triangles, wedges,
                transitivity, averageLocal);
    }

    private static double localClusteringCoefficient(int degree, long triangles) {
        if (degree < 2) {
            return 0.0;
        }
        return 2.0 * triangles / ((double) degree * (degree - 1));
    }
}
