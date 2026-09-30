package com.github.guoyyit.graphtrianglestatsjava.service;

import com.github.guoyyit.graphtrianglestatsjava.config.GraphProperties;
import com.github.guoyyit.graphtrianglestatsjava.domain.Edge;
import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.GraphSnapshot;
import com.github.guoyyit.graphtrianglestatsjava.engine.TriangleStatsEngine;
import com.github.guoyyit.graphtrianglestatsjava.exception.CapacityExceededException;
import com.github.guoyyit.graphtrianglestatsjava.exception.InvalidEventException;
import com.github.guoyyit.graphtrianglestatsjava.exception.UnknownVersionException;
import com.github.guoyyit.graphtrianglestatsjava.wal.WriteAheadLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Application service orchestrating validation, atomic commits, versioned
 * snapshots, crash recovery and bounded capacity.
 *
 * <p>Concurrency model: a single {@link #commitLock} guards all writes, while
 * readers never take a lock. Every commit publishes a brand-new immutable
 * {@link GraphSnapshot} by a single volatile write, so a query always observes
 * a complete snapshot at one committed version, even while writes are in
 * progress.
 */
@Service
public class GraphStatsService {

    private final GraphProperties properties;
    private final Object commitLock = new Object();

    private volatile GraphSnapshot currentSnapshot = GraphSnapshot.empty();
    private final Deque<GraphSnapshot> history = new ArrayDeque<>();
    private TriangleStatsEngine engine;
    private WriteAheadLog writeAheadLog;

    public GraphStatsService(GraphProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void recover() {
        engine = new TriangleStatsEngine();
        Path walDirectory = Path.of(properties.getWalDir());
        WriteAheadLog.truncateAfterLastValidRecord(walDirectory);
        List<WriteAheadLog.CommittedBatch> batches =
                WriteAheadLog.recover(walDirectory);
        long lastVersion = 0;
        for (WriteAheadLog.CommittedBatch batch : batches) {
            for (EdgeEvent event : batch.events()) {
                engine.apply(event);
            }
            lastVersion = batch.version();
            retain(engine.snapshot(batch.version()));
        }
        currentSnapshot = engine.snapshot(lastVersion);
        writeAheadLog = new WriteAheadLog(walDirectory);
    }

    @PreDestroy
    public void shutdown() {
        if (writeAheadLog != null) {
            writeAheadLog.close();
        }
    }

    /**
     * Atomically validates, simulates, persists and publishes a batch.
     * Any validation or capacity failure rejects the entire batch without
     * side effects.
     *
     * @return the newly committed snapshot
     */
    public GraphSnapshot commit(List<EdgeEvent> events) {
        validate(events);
        synchronized (commitLock) {
            TriangleStatsEngine candidate = engine.copy();
            for (EdgeEvent event : events) {
                candidate.apply(event);
            }
            if (candidate.vertexCount() > properties.getMaxVertices()
                    || candidate.edgeCount() > properties.getMaxEdges()) {
                throw new CapacityExceededException(String.format(
                        "batch rejected: graph capacity exceeded (vertices %d/%d, edges %d/%d)",
                        candidate.vertexCount(), properties.getMaxVertices(),
                        candidate.edgeCount(), properties.getMaxEdges()));
            }

            long nextVersion = currentSnapshot.version() + 1;
            GraphSnapshot snapshot = candidate.snapshot(nextVersion);
            writeAheadLog.append(nextVersion, events);

            engine = candidate;
            GraphSnapshot previous = currentSnapshot;
            currentSnapshot = snapshot;
            retain(snapshot);
            return snapshot;
        }
    }

    private void validate(List<EdgeEvent> events) {
        if (events == null || events.isEmpty()) {
            throw new InvalidEventException("batch must contain at least one event");
        }
        for (EdgeEvent event : events) {
            if (event == null || event.operation() == null) {
                throw new InvalidEventException("event and its operation must not be null");
            }
            validateEndpoint(event.source(), "source");
            validateEndpoint(event.target(), "target");
            if (event.source().equals(event.target())) {
                throw new InvalidEventException("self-loops are not supported: " + event.source());
            }
        }
    }

    private void validateEndpoint(String endpoint, String role) {
        if (endpoint == null || endpoint.isEmpty()) {
            throw new InvalidEventException(role + " endpoint must not be empty");
        }
        if (endpoint.length() > properties.getMaxVertexIdLength()) {
            throw new InvalidEventException(
                    role + " endpoint exceeds max id length " + properties.getMaxVertexIdLength());
        }
    }

    private void retain(GraphSnapshot snapshot) {
        history.addLast(snapshot);
        while (history.size() > Math.max(1, properties.getRetainedVersions())) {
            history.removeFirst();
        }
    }

    public GraphSnapshot current() {
        return currentSnapshot;
    }

    public GraphSnapshot atVersion(long version) {
        GraphSnapshot snapshot = currentSnapshot;
        if (snapshot.version() == version) {
            return snapshot;
        }
        synchronized (commitLock) {
            for (GraphSnapshot historical : history) {
                if (historical.version() == version) {
                    return historical;
                }
            }
        }
        throw new UnknownVersionException(
                "version " + version + " is not retained (latest " + snapshot.version() + ")");
    }

    public Edge canonicalEdge(String first, String second) {
        validateEndpoint(first, "source");
        validateEndpoint(second, "target");
        return Edge.of(first, second);
    }
}
