package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventOperation;
import com.github.guoyyit.graphtrianglestatsjava.domain.GraphSnapshot;
import com.github.guoyyit.graphtrianglestatsjava.engine.TriangleStatsEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriangleStatsEngineTest {

    private EdgeEvent add(String source, String target) {
        return new EdgeEvent(EventOperation.ADD_EDGE, source, target);
    }

    private EdgeEvent remove(String source, String target) {
        return new EdgeEvent(EventOperation.REMOVE_EDGE, source, target);
    }

    @Test
    void countsTrianglesOnAK4() {
        TriangleStatsEngine engine = new TriangleStatsEngine();
        engine.apply(add("a", "b"));
        engine.apply(add("b", "c"));
        engine.apply(add("c", "a"));
        engine.apply(add("a", "d"));
        engine.apply(add("b", "d"));
        engine.apply(add("c", "d"));

        GraphSnapshot snapshot = engine.snapshot(1);
        assertEquals(4, snapshot.totalTriangles());
        for (String vertex : List.of("a", "b", "c", "d")) {
            assertEquals(3, snapshot.vertexTriangleCount(vertex));
            assertEquals(1.0, snapshot.localClusteringCoefficient(vertex), 1e-12);
        }
        assertEquals(2, snapshot.edgeTriangleCount("a", "b"));
        assertEquals(1.0, snapshot.globalClusteringCoefficient(), 1e-12);
    }

    @Test
    void updatesCountsAfterEdgeRemoval() {
        TriangleStatsEngine engine = new TriangleStatsEngine();
        for (EdgeEvent event : List.of(add("a", "b"), add("b", "c"), add("c", "a"),
                add("a", "d"), add("b", "d"))) {
            engine.apply(event);
        }
        assertEquals(2, engine.totalTriangles());
        engine.apply(remove("a", "b"));
        GraphSnapshot snapshot = engine.snapshot(2);
        assertEquals(0, snapshot.totalTriangles());
        assertEquals(0, snapshot.edgeTriangleCount("a", "b"));
        assertEquals(0, snapshot.vertexTriangleCount("a"));
        assertEquals(2, snapshot.degrees().get("a"));
    }

    @Test
    void duplicateEventsDoNotChangeStats() {
        TriangleStatsEngine engine = new TriangleStatsEngine();
        List<EdgeEvent> events = List.of(
                add("a", "b"), add("b", "c"), add("c", "a"),
                add("a", "b"), add("b", "a"), add("x", "y"),
                remove("x", "y"), remove("x", "y"), remove("p", "q"));
        for (EdgeEvent event : events) {
            engine.apply(event);
        }
        GraphSnapshot snapshot = engine.snapshot(1);
        assertEquals(1, snapshot.totalTriangles());
        assertEquals(3, snapshot.edgeCount());
        assertEquals(3, snapshot.vertexCount());
    }

    @Test
    void redundantReplaysWithDuplicatesAreStable() {
        List<EdgeEvent> uniqueEvents = List.of(
                add("1", "2"), add("2", "3"), add("3", "4"), add("4", "1"),
                add("1", "3"), add("2", "4"), add("5", "1"), add("5", "2"));

        TriangleStatsEngine firstEngine = new TriangleStatsEngine();
        uniqueEvents.forEach(firstEngine::apply);
        GraphSnapshot first = firstEngine.snapshot(1);

        TriangleStatsEngine secondEngine = new TriangleStatsEngine();
        List<EdgeEvent> withDuplicates = new ArrayList<>(uniqueEvents);
        withDuplicates.addAll(uniqueEvents);
        Collections.shuffle(withDuplicates);
        for (EdgeEvent event : withDuplicates) {
            secondEngine.apply(event);
        }
        GraphSnapshot second = secondEngine.snapshot(1);

        assertEquals(first.totalTriangles(), second.totalTriangles());
        assertEquals(first.vertexTriangles(), second.vertexTriangles());
        assertEquals(first.edgeTriangles(), second.edgeTriangles());
        assertEquals(first.degrees(), second.degrees());
    }

    @Test
    void reportsZeroForUnknownVerticesAndEdges() {
        GraphSnapshot snapshot = new TriangleStatsEngine().snapshot(0);
        assertEquals(0, snapshot.vertexTriangleCount("ghost"));
        assertEquals(0.0, snapshot.localClusteringCoefficient("ghost"), 0);
        assertEquals(0, snapshot.edgeTriangleCount("ghost", "spirit"));
        assertEquals(0.0, snapshot.globalClusteringCoefficient(), 0);
        assertTrue(snapshot.vertexCount() == 0);
    }
}
