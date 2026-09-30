package com.github.guoyyit.graphtrianglestatsjava.domain;

/**
 * Canonical undirected edge: endpoints are ordered lexicographically so that
 * {@code (u, v)} and {@code (v, u)} are the same edge.
 *
 * @param smaller lexicographically smaller endpoint
 * @param larger  lexicographically larger endpoint
 */
public record Edge(String smaller, String larger) {

    public Edge {
        if (smaller.compareTo(larger) > 0) {
            String tmp = smaller;
            smaller = larger;
            larger = tmp;
        }
    }

    public static Edge of(String first, String second) {
        if (first.compareTo(second) <= 0) {
            return new Edge(first, second);
        }
        return new Edge(second, first);
    }
}
