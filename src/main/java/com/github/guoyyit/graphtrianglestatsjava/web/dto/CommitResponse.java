package com.github.guoyyit.graphtrianglestatsjava.web.dto;

/**
 * Result of a successful atomic batch commit.
 */
public record CommitResponse(long version, int vertices, int edges, long triangles) {
}
