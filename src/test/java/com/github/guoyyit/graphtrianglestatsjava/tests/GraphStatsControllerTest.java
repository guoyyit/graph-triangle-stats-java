package com.github.guoyyit.graphtrianglestatsjava.tests;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "graph.wal-path=target/test-wal/controller.log",
        "graph.max-vertices=1000",
        "graph.max-edges=1000"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GraphStatsControllerTest {

    static {
        try {
            Files.deleteIfExists(Path.of("target/test-wal/controller.log"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Order(1)
    void submitBatchAndQueryStats() throws Exception {
        mockMvc.perform(post("/api/graph/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":["
                                + "{\"type\":\"ADD\",\"u\":1,\"v\":2},"
                                + "{\"type\":\"ADD\",\"u\":2,\"v\":3},"
                                + "{\"type\":\"ADD\",\"u\":1,\"v\":3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.appliedEvents").value(3))
                .andExpect(jsonPath("$.ignoredEvents").value(0));

        mockMvc.perform(get("/api/graph/nodes/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triangles").value(1))
                .andExpect(jsonPath("$.degree").value(2))
                .andExpect(jsonPath("$.localClusteringCoefficient").value(1.0));

        mockMvc.perform(get("/api/graph/edges").param("u", "1").param("v", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.triangles").value(1));

        mockMvc.perform(get("/api/graph/global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.triangles").value(1))
                .andExpect(jsonPath("$.globalClusteringCoefficient").value(1.0));
    }

    @Test
    @Order(2)
    void invalidBatchReturns400AndDoesNotConsumeVersion() throws Exception {
        mockMvc.perform(post("/api/graph/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[{\"type\":\"ADD\",\"u\":5,\"v\":5}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/graph/global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @Order(3)
    void malformedBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/graph/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
