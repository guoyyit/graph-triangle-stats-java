package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.config.GraphProperties;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventOperation;
import com.github.guoyyit.graphtrianglestatsjava.domain.GraphSnapshot;
import com.github.guoyyit.graphtrianglestatsjava.exception.CapacityExceededException;
import com.github.guoyyit.graphtrianglestatsjava.exception.InvalidEventException;
import com.github.guoyyit.graphtrianglestatsjava.exception.UnknownVersionException;
import com.github.guoyyit.graphtrianglestatsjava.service.GraphStatsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphStatsServiceTest {

    private GraphStatsService newService(Path walDir) {
        return newService(walDir, 100_000, 100_000, 8);
    }

    private GraphStatsService newService(Path walDir, int maxVertices, int maxEdges, int retained) {
        GraphProperties properties = new GraphProperties();
        properties.setWalDir(walDir.toString());
        properties.setMaxVertices(maxVertices);
        properties.setMaxEdges(maxEdges);
        properties.setRetainedVersions(retained);
        GraphStatsService service = new GraphStatsService(properties);
        service.recover();
        return service;
    }

    private EdgeEvent add(String source, String target) {
        return new EdgeEvent(EventOperation.ADD_EDGE, source, target);
    }

    private EdgeEvent remove(String source, String target) {
        return new EdgeEvent(EventOperation.REMOVE_EDGE, source, target);
    }

    private List<EdgeEvent> k4Edges() {
        return List.of(
                add("a", "b"), add("b", "c"), add("c", "a"),
                add("a", "d"), add("b", "d"), add("c", "d"));
    }

    @Test
    void commitsAreAtomicAndVersionsMonotonic(@TempDir Path walDir) {
        GraphStatsService service = newService(walDir);
        GraphSnapshot first = service.commit(List.of(add("a", "b"), add("b", "c"), add("c", "a")));
        assertEquals(1, first.version());
        assertEquals(1, first.totalTriangles());

        List<EdgeEvent> nullEvent = new ArrayList<>();
        nullEvent.add(add("d", "e"));
        nullEvent.add(null);
        assertThrows(InvalidEventException.class, () -> service.commit(nullEvent));
        assertEquals(1, service.current().version(), "rejected batch must leave state untouched");
        assertEquals(1, service.current().totalTriangles());

        GraphSnapshot second = service.commit(List.of(add("a", "d"), add("b", "d")));
        assertEquals(2, second.version());
        assertEquals(2, second.totalTriangles());
    }

    @Test
    void differentBatchingYieldsSameResult(@TempDir Path walDir) {
        List<EdgeEvent> events = new ArrayList<>(k4Edges());
        events.add(remove("a", "b"));
        events.add(add("e", "a"));

        GraphStatsService oneBatch = newService(walDir.resolve("a"));
        oneBatch.commit(events);
        GraphSnapshot single = oneBatch.current();

        GraphStatsService manyBatches = newService(walDir.resolve("b"));
        for (EdgeEvent event : events) {
            manyBatches.commit(List.of(event));
        }
        GraphSnapshot streamed = manyBatches.current();

        assertEquals(single.totalTriangles(), streamed.totalTriangles());
        assertEquals(single.vertexTriangles(), streamed.vertexTriangles());
        assertEquals(single.edgeTriangles(), streamed.edgeTriangles());
        assertEquals(single.degrees(), streamed.degrees());
        assertEquals(5, single.vertexCount());
        assertEquals(events.size(), (int) streamed.version());
    }

    @Test
    void duplicateCommittedEventsAreIdempotent(@TempDir Path walDir) {
        GraphStatsService service = newService(walDir);
        service.commit(List.of(add("a", "b"), add("b", "c"), add("c", "a")));
        service.commit(List.of(add("a", "b"), add("b", "a"), add("a", "b")));
        GraphSnapshot snapshot = service.current();
        assertEquals(1, snapshot.totalTriangles());
        assertEquals(3, snapshot.edgeCount());
        assertEquals(2, snapshot.version());
    }

    @Test
    void rejectsBatchExceedingCapacityAtomically(@TempDir Path walDir) {
        GraphStatsService service = newService(walDir.resolve("cap"), 4, 100, 8);
        service.commit(k4Edges());
        GraphSnapshot before = service.current();

        assertThrows(CapacityExceededException.class,
                () -> service.commit(List.of(add("a", "e"), add("b", "e"), add("f", "g"))));
        assertEquals(before, service.current());

        assertThrows(CapacityExceededException.class,
                () -> service.commit(List.of(add("a", "e"), add("b", "e"), add("c", "e"), add("d", "e"))));
        assertEquals(before, service.current());
    }

    @Test
    void invalidEndpointRejectsWholeBatch(@TempDir Path walDir) {
        GraphStatsService service = newService(walDir);
        GraphSnapshot empty = service.current();
        assertThrows(InvalidEventException.class,
                () -> service.commit(List.of(add("a", "b"), add("", "c"))));
        assertThrows(InvalidEventException.class,
                () -> service.commit(List.of(add("a", "a"))));
        assertEquals(empty, service.current());
    }

    @Test
    void queriesTargetRetainedVersionsAndRejectOldOnes(@TempDir Path walDir) {
        GraphStatsService service = newService(walDir.resolve("v"), 100, 100, 2);
        service.commit(List.of(add("a", "b"), add("b", "c"), add("c", "a")));
        service.commit(List.of(add("a", "d")));
        service.commit(List.of(add("b", "d")));

        assertEquals(3, service.current().version());
        GraphSnapshot atTwo = service.atVersion(2);
        assertEquals(2, atTwo.version());
        assertEquals(1, atTwo.totalTriangles());
        assertTrue(service.atVersion(3).totalTriangles() >= 2);
        assertThrows(UnknownVersionException.class, () -> service.atVersion(1));
    }

    @Test
    void recoversStateAfterRestartWithoutReplayingCommittedEffectsTwice(@TempDir Path walDir) {
        GraphStatsService first = newService(walDir);
        first.commit(k4Edges());
        first.commit(List.of(remove("a", "b")));
        GraphSnapshot beforeCrash = first.current();
        first.shutdown();

        GraphStatsService restarted = newService(walDir);
        GraphSnapshot recovered = restarted.current();
        assertEquals(beforeCrash.version(), recovered.version());
        assertEquals(beforeCrash.totalTriangles(), recovered.totalTriangles());
        assertEquals(beforeCrash.vertexTriangles(), recovered.vertexTriangles());
        assertEquals(beforeCrash.edgeTriangles(), recovered.edgeTriangles());
        assertEquals(beforeCrash.degrees(), recovered.degrees());

        GraphSnapshot continued = restarted.commit(List.of(add("a", "b")));
        assertEquals(beforeCrash.version() + 1, continued.version());
        assertEquals(4, continued.totalTriangles());
    }

    @ParameterizedTest(name = "crash-recovery cycle with torn tail of {0} byte(s)")
    @ValueSource(ints = {1, 2, 3, 4, 5, 7, 8, 16, 33})
    void crashRecoveryCycleWithTornTailOfAnyLength(int residueLength,
                                                   @TempDir Path walDir) throws Exception {
        GraphStatsService first = newService(walDir);
        first.commit(k4Edges());
        first.commit(List.of(remove("a", "b")));
        GraphSnapshot beforeFirstCrash = first.current();
        first.shutdown();
        appendResidue(walDir, residueLength);

        GraphStatsService second = newService(walDir);
        assertSnapshotEquals(beforeFirstCrash, second.current(),
                "restart after torn tail of " + residueLength + " byte(s)");

        GraphSnapshot continued = second.commit(List.of(add("a", "b"), add("e", "f")));
        assertEquals(beforeFirstCrash.version() + 1, continued.version(),
                "version must continue from the recovered version");
        second.shutdown();
        appendResidue(walDir, residueLength + 1);

        GraphStatsService third = newService(walDir);
        assertSnapshotEquals(continued, third.current(),
                "batch committed after residue cleanup must survive the next crash");
        third.shutdown();

        GraphStatsService fourth = newService(walDir);
        assertSnapshotEquals(continued, fourth.current(),
                "repeated recovery over the same log must be idempotent");
        fourth.shutdown();
    }

    private static void appendResidue(Path walDir, int residueLength) throws Exception {
        byte[] residue = new byte[residueLength];
        for (int index = 0; index < residue.length; index++) {
            residue[index] = (byte) (0xA5 + index);
        }
        Files.write(walDir.resolve("wal.log"), residue, StandardOpenOption.APPEND);
    }

    private static void assertSnapshotEquals(GraphSnapshot expected, GraphSnapshot actual,
                                             String scenario) {
        assertEquals(expected.version(), actual.version(), scenario + ": version");
        assertEquals(expected.totalTriangles(), actual.totalTriangles(),
                scenario + ": totalTriangles");
        assertEquals(expected.totalWedges(), actual.totalWedges(), scenario + ": totalWedges");
        assertEquals(expected.vertexTriangles(), actual.vertexTriangles(),
                scenario + ": vertexTriangles");
        assertEquals(expected.edgeTriangles(), actual.edgeTriangles(), scenario + ": edgeTriangles");
        assertEquals(expected.degrees(), actual.degrees(), scenario + ": degrees");
        assertEquals(expected.localClustering(), actual.localClustering(),
                scenario + ": localClustering");
    }
}
