package com.github.guoyyit.graphtrianglestatsjava.domain;

public record Edge(long u, long v) {

    public Edge {
        if (u > v) {
            long tmp = u;
            u = v;
            v = tmp;
        }
    }

    public static Edge of(long a, long b) {
        return new Edge(a, b);
    }
}
