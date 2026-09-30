package com.github.guoyyit.graphtrianglestatsjava.web.dto;

/**
 * Whole-graph triangle count and clustering coefficients.
 */
public record GraphStatsResponse(
        long version,
        int vertices,
        int edges,
        long triangles,
        long wedges,
        double globalClusteringCoefficient,
        double averageLocalClusteringCoefficient) {
}
