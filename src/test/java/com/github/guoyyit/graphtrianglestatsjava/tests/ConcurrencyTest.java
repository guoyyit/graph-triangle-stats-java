package com.github.guoyyit.graphtrianglestatsjava.tests;

import com.github.guoyyit.graphtrianglestatsjava.config.GraphProperties;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventType;
import com.github.guoyyit.graphtrianglestatsjava.service.GraphStatsService;
import com.github.guoyyit.graphtrianglestatsjava.stats.GlobalStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    @TempDir
    Path tempDir;

    private static EdgeEvent add(long u, long v) {
        return new EdgeEvent(EventType.ADD, u, v);
    }

    @Test
    void readersNeverObservePartiallyAppliedBatches() throws Exception {
        GraphProperties properties = new GraphProperties();
        properties.setWalPath(tempDir.resolve("concurrent.log").toString());
        int writers = 4;
        int batchesPerWriter = 50;
        try (GraphStatsService service = new GraphStatsService(properties)) {
            ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicReference<Throwable> readerFailure = new AtomicReference<>();
            List<Future<?>> writerFutures = new ArrayList<>();
            List<Future<?>> readerFutures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writerId = w;
                writerFutures.add(pool.submit(() -> {
                    await(start);
                    for (int batch = 0; batch < batchesPerWriter; batch++) {
                        long base = (writerId * batchesPerWriter + batch) * 3L;
                        service.submitBatch(List.of(
                                add(base, base + 1), add(base + 1, base + 2), add(base, base + 2)));
                    }
                    return null;
                }));
            }
            for (int r = 0; r < 2; r++) {
                readerFutures.add(pool.submit(() -> {
                    await(start);
                    long lastVersion = 0;
                    while (!stop.get()) {
                        long versionBefore = service.currentVersion();
                        GlobalStats stats = service.globalStats();
                        long versionAfter = service.currentVersion();
                        try {
                            assertTrue(stats.version() >= versionBefore && stats.version() <= versionAfter,
                                    "snapshot version must be a committed version");
                            assertTrue(stats.version() >= lastVersion, "versions must not go backwards");
                            assertEquals(stats.triangles() * 3, stats.wedges(),
                                    "partial batch visible: wedges mismatch");
                            assertEquals(stats.triangles() * 3, stats.edgeCount(),
                                    "partial batch visible: edge count mismatch");
                            assertEquals(stats.triangles() * 3, stats.vertexCount(),
                                    "partial batch visible: vertex count mismatch");
                        } catch (Throwable t) {
                            readerFailure.compareAndSet(null, t);
                            stop.set(true);
                            return null;
                        }
                        lastVersion = stats.version();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : writerFutures) {
                future.get(60, TimeUnit.SECONDS);
            }
            stop.set(true);
            for (Future<?> future : readerFutures) {
                future.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
            assertNull(readerFailure.get(), () -> "reader observed inconsistent snapshot: " + readerFailure.get());
            int totalBatches = writers * batchesPerWriter;
            assertEquals(totalBatches, service.currentVersion());
            GlobalStats stats = service.globalStats();
            assertEquals(totalBatches, stats.triangles());
            assertEquals(totalBatches * 3L, stats.edgeCount());
            assertEquals(totalBatches * 3L, stats.wedges());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
