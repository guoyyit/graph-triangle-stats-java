package com.github.guoyyit.graphtrianglestatsjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * @author Vincent
 * @version 1.0
 */
@SpringBootTest(properties = "graph.wal-path=target/test-wal/application.log")
public class ApplicationTests {
    @Test
    public void contentLoad() {
    }
}
