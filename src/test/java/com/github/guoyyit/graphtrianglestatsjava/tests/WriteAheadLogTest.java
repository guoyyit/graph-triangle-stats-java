package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventOperation;
import com.github.guoyyit.graphtrianglestatsjava.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriteAheadLogTest {

    private EdgeEvent add(String source, String target) {
        return new EdgeEvent(EventOperation.ADD_EDGE, source, target);
    }

    @Test
    void replaysCommittedBatchesInOrder(@TempDir Path directory) {
        List<EdgeEvent> first = List.of(add("a", "b"), add("b", "c"));
        List<EdgeEvent> second = List.of(new EdgeEvent(EventOperation.REMOVE_EDGE, "a", "b"));
        try (WriteAheadLog log = new WriteAheadLog(directory)) {
            log.append(1, first);
            log.append(2, second);
        }

        List<WriteAheadLog.CommittedBatch> recovered = WriteAheadLog.recover(directory);
        assertEquals(2, recovered.size());
        assertEquals(1, recovered.get(0).version());
        assertEquals(first, recovered.get(0).events());
        assertEquals(2, recovered.get(1).version());
        assertEquals(second, recovered.get(1).events());
    }

    @Test
    void ignoresAndTruncatesTornTrailingRecord(@TempDir Path directory) throws Exception {
        try (WriteAheadLog log = new WriteAheadLog(directory)) {
            log.append(1, List.of(add("a", "b")));
        }
        Path logFile = directory.resolve("wal.log");
        long goodSize = Files.size(logFile);
        byte[] good = Files.readAllBytes(logFile);
        byte[] torn = new byte[good.length + 7];
        System.arraycopy(good, 0, torn, 0, good.length);
        for (int index = good.length; index < torn.length; index++) {
            torn[index] = (byte) 0xFF;
        }
        Files.write(logFile, torn);

        WriteAheadLog.truncateAfterLastValidRecord(directory);
        assertEquals(goodSize, Files.size(logFile));

        List<WriteAheadLog.CommittedBatch> recovered = WriteAheadLog.recover(directory);
        assertEquals(1, recovered.size());
        assertEquals(1, recovered.get(0).version());
    }

    @ParameterizedTest(name = "torn tail of {0} byte(s) is truncated")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 31, 64})
    void truncatesTornTailOfAnyLength(int residueLength, @TempDir Path directory) throws Exception {
        try (WriteAheadLog log = new WriteAheadLog(directory)) {
            log.append(1, List.of(add("a", "b")));
            log.append(2, List.of(add("b", "c")));
        }
        Path logFile = directory.resolve("wal.log");
        long goodSize = Files.size(logFile);
        appendResidue(logFile, residueLength);

        WriteAheadLog.truncateAfterLastValidRecord(directory);
        assertEquals(goodSize, Files.size(logFile),
                "residue of " + residueLength + " byte(s) must be truncated");

        List<WriteAheadLog.CommittedBatch> recovered = WriteAheadLog.recover(directory);
        assertEquals(2, recovered.size());
        assertEquals(1, recovered.get(0).version());
        assertEquals(2, recovered.get(1).version());
    }

    @ParameterizedTest(name = "batches committed after {0} byte(s) of residue survive recovery")
    @ValueSource(ints = {1, 2, 3, 4, 5, 7, 8, 13, 32})
    void batchesCommittedAfterResidueSurviveNextRecovery(int residueLength,
                                                         @TempDir Path directory) throws Exception {
        try (WriteAheadLog log = new WriteAheadLog(directory)) {
            log.append(1, List.of(add("a", "b"), add("b", "c"), add("c", "a")));
        }
        Path logFile = directory.resolve("wal.log");
        appendResidue(logFile, residueLength);

        WriteAheadLog.truncateAfterLastValidRecord(directory);
        assertEquals(1, WriteAheadLog.recover(directory).size());

        try (WriteAheadLog log = new WriteAheadLog(directory)) {
            log.append(2, List.of(add("a", "d")));
        }

        WriteAheadLog.truncateAfterLastValidRecord(directory);
        List<WriteAheadLog.CommittedBatch> recovered = WriteAheadLog.recover(directory);
        assertEquals(2, recovered.size(),
                "batch committed after residue cleanup must not be lost on next recovery");
        assertEquals(1, recovered.get(0).version());
        assertEquals(2, recovered.get(1).version());
        assertEquals(List.of(add("a", "d")), recovered.get(1).events());

        WriteAheadLog.truncateAfterLastValidRecord(directory);
        List<WriteAheadLog.CommittedBatch> recoveredAgain = WriteAheadLog.recover(directory);
        assertEquals(recovered, recoveredAgain, "recovery must be idempotent");
    }

    private static void appendResidue(Path logFile, int residueLength) throws Exception {
        byte[] residue = new byte[residueLength];
        for (int index = 0; index < residue.length; index++) {
            residue[index] = (byte) (0xA5 + index);
        }
        Files.write(logFile, residue, StandardOpenOption.APPEND);
    }
}
