package com.github.guoyyit.graphtrianglestatsjava.web.dto;

/**
 * Number of triangles containing a given edge.
 */
public record EdgeStatsResponse(
        long version,
        String source,
        String target,
        boolean exists,
        int triangles) {
}
