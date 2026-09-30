package com.github.guoyyit.graphtrianglestatsjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the Spring context boots with an isolated test WAL directory.
 */
@SpringBootTest(properties = "graph.stats.wal-dir=target/test-wal/context")
public class ApplicationTests {
    @Test
    public void contentLoad() {
    }
}
