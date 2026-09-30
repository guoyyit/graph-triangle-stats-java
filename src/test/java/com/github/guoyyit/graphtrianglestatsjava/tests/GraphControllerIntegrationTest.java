package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "graph.stats.wal-dir=target/test-wal/integration",
        "graph.stats.max-vertices=10",
        "graph.stats.max-edges=20"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    void cleanWal() throws Exception {
        Path wal = Path.of("target/test-wal/integration/wal.log");
        java.nio.file.Files.deleteIfExists(wal);
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        String triangleBatch = """
                {"events":[
                  {"operation":"ADD_EDGE","source":"a","target":"b"},
                  {"operation":"ADD_EDGE","source":"b","target":"c"},
                  {"operation":"ADD_EDGE","source":"c","target":"a"}
                ]}
                """;
        mockMvc.perform(post("/api/graph/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(triangleBatch))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.triangles").value(1));

        mockMvc.perform(get("/api/graph/triangles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.triangles").value(1))
                .andExpect(jsonPath("$.globalClusteringCoefficient").value(1.0));

        mockMvc.perform(get("/api/graph/vertices/a/triangles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triangles").value(1))
                .andExpect(jsonPath("$.clusteringCoefficient").value(1.0));

        mockMvc.perform(get("/api/graph/edges/triangles").param("source", "a").param("target", "b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.triangles").value(1));

        String reversedDuplicates = """
                {"events":[
                  {"operation":"ADD_EDGE","source":"b","target":"a"},
                  {"operation":"ADD_EDGE","source":"a","target":"b"}
                ]}
                """;
        mockMvc.perform(post("/api/graph/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversedDuplicates))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.triangles").value(1));

        mockMvc.perform(get("/api/graph/triangles").param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/graph/triangles").param("version", "999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_VERSION"));
    }

    @Test
    void invalidEventReturnsBadRequestWithoutEffect() throws Exception {
        String invalid = """
                {"events":[
                  {"operation":"ADD_EDGE","source":"a","target":"b"},
                  {"operation":"ADD_EDGE","source":"","target":"c"}
                ]}
                """;
        mockMvc.perform(post("/api/graph/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EVENT"));

        mockMvc.perform(get("/api/graph/triangles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triangles").value(1));
    }

    @Test
    void capacityRejectionIsDistinguishable() throws Exception {
        String oversized = """
                {"events":[
                  {"operation":"ADD_EDGE","source":"n1","target":"n2"},
                  {"operation":"ADD_EDGE","source":"n3","target":"n4"},
                  {"operation":"ADD_EDGE","source":"n5","target":"n6"},
                  {"operation":"ADD_EDGE","source":"n7","target":"n8"},
                  {"operation":"ADD_EDGE","source":"n9","target":"n10"},
                  {"operation":"ADD_EDGE","source":"n11","target":"n12"}
                ]}
                """;
        mockMvc.perform(post("/api/graph/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isInsufficientStorage())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));

        String response = mockMvc.perform(get("/api/graph/triangles"))
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        org.junit.jupiter.api.Assertions.assertEquals(3, body.get("vertices").asInt());
    }
}
