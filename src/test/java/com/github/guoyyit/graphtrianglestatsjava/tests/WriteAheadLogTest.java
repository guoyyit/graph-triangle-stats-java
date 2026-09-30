package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventOperation;
import com.github.guoyyit.graphtrianglestatsjava.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
