package com.github.guoyyit.graphtrianglestatsjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "graph")
public class GraphProperties {

    private int maxVertices = 1_000_000;
    private long maxEdges = 10_000_000;
    private String walPath = "data/graph-wal.log";

    public int getMaxVertices() {
        return maxVertices;
    }

    public void setMaxVertices(int maxVertices) {
        this.maxVertices = maxVertices;
    }

    public long getMaxEdges() {
        return maxEdges;
    }

    public void setMaxEdges(long maxEdges) {
        this.maxEdges = maxEdges;
    }

    public String getWalPath() {
        return walPath;
    }

    public void setWalPath(String walPath) {
        this.walPath = walPath;
    }
}
