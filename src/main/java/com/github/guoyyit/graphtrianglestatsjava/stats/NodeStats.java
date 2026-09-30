package com.github.guoyyit.graphtrianglestatsjava.stats;

public record NodeStats(long nodeId, int degree, long triangles, double localClusteringCoefficient) {
}
