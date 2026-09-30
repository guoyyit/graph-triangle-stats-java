package com.github.guoyyit.graphtrianglestatsjava.web.dto;

/**
 * Per-vertex triangle count and local clustering coefficient.
 */
public record VertexStatsResponse(
        long version,
        String vertex,
        int degree,
        int triangles,
        double clusteringCoefficient) {
}
