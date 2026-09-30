package com.github.guoyyit.graphtrianglestatsjava.stats;

public record GlobalStats(long version,
                          int vertexCount,
                          long edgeCount,
                          long triangles,
                          long wedges,
                          double globalClusteringCoefficient,
                          double averageLocalClusteringCoefficient) {
}
