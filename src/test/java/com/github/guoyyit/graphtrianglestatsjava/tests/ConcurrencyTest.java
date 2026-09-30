package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.config.GraphProperties;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventOperation;
import com.github.guoyyit.graphtrianglestatsjava.domain.GraphSnapshot;
import com.github.guoyyit.graphtrianglestatsjava.service.GraphStatsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    @Test
    void readersAlwaysObserveConsistentSnapshotsDuringWrites(@TempDir Path walDir) throws Exception {
        GraphProperties properties = new GraphProperties();
        properties.setWalDir(walDir.toString());
        properties.setMaxVertices(100);
        properties.setMaxEdges(100_000);
        properties.setRetainedVersions(64);
        GraphStatsService service = new GraphStatsService(properties);
        service.recover();

        int writerCount = 4;
        int readerCount = 4;
        int batchesPerWriter = 200;
        ExecutorService pool = Executors.newFixedThreadPool(writerCount + readerCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int writer = 0; writer < writerCount; writer++) {
            final int writerId = writer;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int index = 0; index < batchesPerWriter; index++) {
                        int value = (writerId + index) % 40;
                        service.commit(List.of(edge(value), edge((value + 1) % 40), edge((value + 2) % 40)));
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });
        }
        for (int reader = 0; reader < readerCount; reader++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (service.current().version() < writerCount * batchesPerWriter) {
                        GraphSnapshot snapshot = service.current();
                        assertSnapshotSelfConsistent(snapshot);
                    }
                    assertSnapshotSelfConsistent(service.current());
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish in time");
        assertEquals(null, failure.get(), () -> "worker failure: " + failure.get());

        GraphSnapshot finalSnapshot = service.current();
        assertEquals((long) writerCount * batchesPerWriter, finalSnapshot.version());
        assertSnapshotSelfConsistent(finalSnapshot);
    }

    private static EdgeEvent edge(int value) {
        return new EdgeEvent(EventOperation.ADD_EDGE, "v" + value, "v" + ((value + 1) % 40));
    }

    private static void assertSnapshotSelfConsistent(GraphSnapshot snapshot) {
        long summedByVertex = 0;
        for (int triangles : snapshot.vertexTriangles().values()) {
            summedByVertex += triangles;
        }
        assertEquals(3 * snapshot.totalTriangles(), summedByVertex,
                "version " + snapshot.version() + " vertex triangle sum mismatch");

        long summedByEdge = 0;
        for (int triangles : snapshot.edgeTriangles().values()) {
            summedByEdge += triangles;
        }
        assertEquals(3 * snapshot.totalTriangles(), summedByEdge,
                "version " + snapshot.version() + " edge triangle sum mismatch");

        double global = snapshot.globalClusteringCoefficient();
        assertTrue(global >= 0.0 && global <= 1.0,
                "clustering coefficient out of range: " + global);
    }
}
