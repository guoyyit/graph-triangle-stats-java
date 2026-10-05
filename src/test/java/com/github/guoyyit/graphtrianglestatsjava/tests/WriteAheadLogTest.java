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

    /**
     * A crash can leave a remnant of any length at the tail. Cleanup must
     * behave identically for every remnant length: the log returns to the end
     * of the last complete valid record.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 31, 64})
    void truncatesRemnantOfAnyLength(int remnantLength, @TempDir Path directory) throws Exception {
        try (WriteAheadLog log = new WriteAheadLog(directory)) {
            log.append(1, List.of(add("a", "b")));
            log.append(2, List.of(add("b", "c")));
        }
        Path logFile = directory.resolve("wal.log");
        long goodSize = Files.size(logFile);
        byte[] remnant = new byte[remnantLength];
        for (int index = 0; index < remnant.length; index++) {
            remnant[index] = (byte) (0xA5 + index);
        }
        Files.write(logFile, remnant, StandardOpenOption.APPEND);
        assertEquals(goodSize + remnantLength, Files.size(logFile));

        WriteAheadLog.truncateAfterLastValidRecord(directory);
        assertEquals(goodSize, Files.size(logFile),
                "remnant of " + remnantLength + " bytes must be removed");

        List<WriteAheadLog.CommittedBatch> recovered = WriteAheadLog.recover(directory);
        assertEquals(2, recovered.size());
        assertEquals(1, recovered.get(0).version());
        assertEquals(2, recovered.get(1).version());
    }

    /**
     * Simulates a crash mid-append: every possible proper prefix of the next
     * frame (partial header, header only, header plus partial payload) is a
     * torn record and must be cleaned exactly like any other remnant.
     */
    @Test
    void truncatesEveryPrefixOfTornFrame(@TempDir Path directory) throws Exception {
        List<EdgeEvent> first = List.of(add("a", "b"), add("b", "c"));
        List<EdgeEvent> second = List.of(add("c", "d"));
        try (WriteAheadLog log = new WriteAheadLog(directory)) {
            log.append(1, first);
        }
        Path logFile = directory.resolve("wal.log");
        long goodSize = Files.size(logFile);

        Path frameSource = directory.resolve("frame-source");
        try (WriteAheadLog log = new WriteAheadLog(frameSource)) {
            log.append(2, second);
        }
        byte[] fullFrame = Files.readAllBytes(frameSource.resolve("wal.log"));

        for (int prefix = 1; prefix < fullFrame.length; prefix++) {
            Files.write(logFile, java.util.Arrays.copyOf(fullFrame, prefix), StandardOpenOption.APPEND);
            WriteAheadLog.truncateAfterLastValidRecord(directory);
            assertEquals(goodSize, Files.size(logFile),
                    "torn frame prefix of " + prefix + " bytes must be removed");
            List<WriteAheadLog.CommittedBatch> recovered = WriteAheadLog.recover(directory);
            assertEquals(1, recovered.size());
            assertEquals(1, recovered.get(0).version());
            assertEquals(first, recovered.get(0).events());
        }
    }

    /**
     * Full crash cycle: remnant left by a crash is cleaned on restart, new
     * batches committed afterwards are appended to a clean tail, and a second
     * restart recovers every committed batch, old and new.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 7, 8, 13, 40})
    void batchesCommittedAfterCleanupSurviveNextRestart(int remnantLength,
                                                        @TempDir Path directory) throws Exception {
        try (WriteAheadLog log = new WriteAheadLog(directory)) {
            log.append(1, List.of(add("a", "b")));
            log.append(2, List.of(add("b", "c")));
        }
        Path logFile = directory.resolve("wal.log");
        Files.write(logFile, new byte[remnantLength], StandardOpenOption.APPEND);

        WriteAheadLog.truncateAfterLastValidRecord(directory);
        assertEquals(2, WriteAheadLog.recover(directory).size());

        try (WriteAheadLog log = new WriteAheadLog(directory)) {
            log.append(3, List.of(add("c", "d")));
            log.append(4, List.of(add("d", "e")));
        }

        WriteAheadLog.truncateAfterLastValidRecord(directory);
        List<WriteAheadLog.CommittedBatch> recovered = WriteAheadLog.recover(directory);
        assertEquals(4, recovered.size(),
                "batches committed after remnant cleanup must not be lost");
        for (int index = 0; index < recovered.size(); index++) {
            assertEquals(index + 1, recovered.get(index).version());
        }
    }

    /**
     * Recovery is idempotent: repeating truncate + replay over the same log
     * yields the same batches every time and never re-applies or drops any.
     */
    @Test
    void repeatedRecoveryIsIdempotent(@TempDir Path directory) throws Exception {
        try (WriteAheadLog log = new WriteAheadLog(directory)) {
            log.append(1, List.of(add("a", "b")));
            log.append(2, List.of(add("b", "c")));
        }
        Path logFile = directory.resolve("wal.log");
        Files.write(logFile, new byte[] {1, 2, 3}, StandardOpenOption.APPEND);

        WriteAheadLog.truncateAfterLastValidRecord(directory);
        List<WriteAheadLog.CommittedBatch> first = WriteAheadLog.recover(directory);
        long sizeAfterFirstPass = Files.size(logFile);

        for (int attempt = 0; attempt < 3; attempt++) {
            WriteAheadLog.truncateAfterLastValidRecord(directory);
            assertEquals(sizeAfterFirstPass, Files.size(logFile),
                    "cleanup on an already clean log must be a no-op");
            assertEquals(first, WriteAheadLog.recover(directory));
        }
        assertEquals(2, first.size());
        assertEquals(2, first.get(1).version());
    }
}
