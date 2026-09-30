package com.github.guoyyit.graphtrianglestatsjava.domain;

import java.util.Map;

/**
 * Immutable committed snapshot of graph statistics at one version.
 *
 * <p>A snapshot is a complete, self-contained view: concurrent writes always
 * create a new snapshot instead of mutating this one, so every query observes
 * the full result of some committed batch.
 *
 * @param version          monotonically increasing commit version
 * @param vertexTriangles  triangles incident to each vertex
 * @param edgeTriangles    triangles containing each existing edge
 * @param degrees          degree of every vertex
 * @param totalTriangles   number of triangles in the whole graph
 * @param totalWedges      number of length-2 paths (open or closed)
 * @param localClustering  local clustering coefficient of each vertex
 */
public record GraphSnapshot(
        long version,
        Map<String, Integer> vertexTriangles,
        Map<Edge, Integer> edgeTriangles,
        Map<String, Integer> degrees,
        long totalTriangles,
        long totalWedges,
        Map<String, Double> localClustering) {

    public static GraphSnapshot empty() {
        return new GraphSnapshot(0L, Map.of(), Map.of(), Map.of(), 0L, 0L, Map.of());
    }

    public GraphSnapshot {
        vertexTriangles = Map.copyOf(vertexTriangles);
        edgeTriangles = Map.copyOf(edgeTriangles);
        degrees = Map.copyOf(degrees);
        localClustering = Map.copyOf(localClustering);
    }

    public int vertexTriangleCount(String vertex) {
        return vertexTriangles.getOrDefault(vertex, 0);
    }

    public int edgeTriangleCount(String first, String second) {
        return edgeTriangles.getOrDefault(Edge.of(first, second), 0);
    }

    public double localClusteringCoefficient(String vertex) {
        return localClustering.getOrDefault(vertex, 0.0);
    }

    /**
     * Global transitivity: {@code 3 * triangles / wedges}.
     * Returns {@code 0} when the graph has no wedges.
     */
    public double globalClusteringCoefficient() {
        return totalWedges == 0 ? 0.0 : (3.0 * totalTriangles) / totalWedges;
    }

    /**
     * Mean of the local clustering coefficients over all vertices;
     * isolated vertices contribute {@code 0}.
     */
    public double averageLocalClusteringCoefficient() {
        if (degrees.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double coefficient : localClustering.values()) {
            sum += coefficient;
        }
        return sum / degrees.size();
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
}
