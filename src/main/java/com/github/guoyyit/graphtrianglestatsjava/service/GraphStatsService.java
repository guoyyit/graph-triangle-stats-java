package com.github.guoyyit.graphtrianglestatsjava.service;

import com.github.guoyyit.graphtrianglestatsjava.config.GraphProperties;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.stats.EdgeStats;
import com.github.guoyyit.graphtrianglestatsjava.stats.GlobalStats;
import com.github.guoyyit.graphtrianglestatsjava.stats.NodeStats;
import com.github.guoyyit.graphtrianglestatsjava.store.BatchResult;
import com.github.guoyyit.graphtrianglestatsjava.store.GraphStatsStore;
import com.github.guoyyit.graphtrianglestatsjava.wal.CommittedBatch;
import com.github.guoyyit.graphtrianglestatsjava.wal.WriteAheadLog;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Coordinates validation, journaling and in-memory application of event
 * batches. Writers hold an exclusive lock so a batch commits atomically
 * (validate -&gt; append WAL -&gt; apply); readers hold a shared lock and
 * therefore always observe a fully committed snapshot at a single version.
 */
@Service
public class GraphStatsService implements AutoCloseable {

    private final GraphStatsStore store;
    private final WriteAheadLog wal;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public GraphStatsService(GraphProperties properties) {
        this.store = new GraphStatsStore(properties.getMaxVertices(), properties.getMaxEdges());
        try {
            this.wal = new WriteAheadLog(Path.of(properties.getWalPath()));
            for (CommittedBatch batch : wal.recover()) {
                store.applyCommittedBatch(batch.version(), batch.events());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open or recover write-ahead log", e);
        }
    }

    /**
     * Validates and commits a batch atomically. If any event is invalid or
     * the batch would exceed capacity, the whole batch is rejected and no
     * version is consumed. On success the batch is journaled before it is
     * applied, and the returned version is one greater than the previous
     * committed version.
     */
    public BatchResult submitBatch(List<EdgeEvent> events) {
        lock.writeLock().lock();
        try {
            store.validateBatch(events);
            long nextVersion = store.currentVersion() + 1;
            wal.append(nextVersion, events);
            return store.applyCommittedBatch(nextVersion, events);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to journal batch", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public NodeStats nodeStats(long node) {
        lock.readLock().lock();
        try {
            return store.nodeStats(node);
        } finally {
            lock.readLock().unlock();
        }
    }

    public EdgeStats edgeStats(long u, long v) {
        lock.readLock().lock();
        try {
            return store.edgeStats(u, v);
        } finally {
            lock.readLock().unlock();
        }
    }

    public GlobalStats globalStats() {
        lock.readLock().lock();
        try {
            return store.globalStats();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long currentVersion() {
        lock.readLock().lock();
        try {
            return store.currentVersion();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            wal.close();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to close write-ahead log", e);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
