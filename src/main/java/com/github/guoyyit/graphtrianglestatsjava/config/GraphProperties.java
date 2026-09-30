package com.github.guoyyit.graphtrianglestatsjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for graph statistics service.
 */
@ConfigurationProperties(prefix = "graph.stats")
public class GraphProperties {

    private int maxVertices = 100_000;
    private int maxEdges = 500_000;
    private int maxVertexIdLength = 256;
    private int retainedVersions = 16;
    private String walDir = "data/wal";

    public int getMaxVertices() {
        return maxVertices;
    }

    public void setMaxVertices(int maxVertices) {
        this.maxVertices = maxVertices;
    }

    public int getMaxEdges() {
        return maxEdges;
    }

    public void setMaxEdges(int maxEdges) {
        this.maxEdges = maxEdges;
    }

    public int getMaxVertexIdLength() {
        return maxVertexIdLength;
    }

    public void setMaxVertexIdLength(int maxVertexIdLength) {
        this.maxVertexIdLength = maxVertexIdLength;
    }

    public int getRetainedVersions() {
        return retainedVersions;
    }

    public void setRetainedVersions(int retainedVersions) {
        this.retainedVersions = retainedVersions;
    }

    public String getWalDir() {
        return walDir;
    }

    public void setWalDir(String walDir) {
        this.walDir = walDir;
    }
}
