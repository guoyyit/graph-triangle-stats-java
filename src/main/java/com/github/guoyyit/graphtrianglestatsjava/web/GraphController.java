package com.github.guoyyit.graphtrianglestatsjava.web;

import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.GraphSnapshot;
import com.github.guoyyit.graphtrianglestatsjava.exception.InvalidEventException;
import com.github.guoyyit.graphtrianglestatsjava.service.GraphStatsService;
import com.github.guoyyit.graphtrianglestatsjava.web.dto.BatchRequest;
import com.github.guoyyit.graphtrianglestatsjava.web.dto.CommitResponse;
import com.github.guoyyit.graphtrianglestatsjava.web.dto.EdgeStatsResponse;
import com.github.guoyyit.graphtrianglestatsjava.web.dto.EventRequest;
import com.github.guoyyit.graphtrianglestatsjava.web.dto.GraphStatsResponse;
import com.github.guoyyit.graphtrianglestatsjava.web.dto.VertexStatsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for edge events and statistics queries.
 *
 * <p>All queries accept an optional {@code version} parameter; when omitted
 * they read the latest committed snapshot. Every response carries the version
 * it was computed at.
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphStatsService service;

    public GraphController(GraphStatsService service) {
        this.service = service;
    }

    @PostMapping("/events")
    public ResponseEntity<CommitResponse> submit(@RequestBody BatchRequest request) {
        if (request == null || request.events() == null) {
            throw new InvalidEventException("request body must contain an events list");
        }
        List<EdgeEvent> events = request.events().stream()
                .map(this::toDomain)
                .toList();
        GraphSnapshot snapshot = service.commit(events);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CommitResponse(
                snapshot.version(),
                snapshot.vertexCount(),
                snapshot.edgeCount(),
                snapshot.totalTriangles()));
    }

    private EdgeEvent toDomain(EventRequest request) {
        if (request == null) {
            throw new InvalidEventException("event must not be null");
        }
        return new EdgeEvent(request.operation(), request.source(), request.target());
    }

    @GetMapping("/vertices/{vertex}/triangles")
    public VertexStatsResponse vertexTriangles(
            @PathVariable String vertex,
            @RequestParam(required = false) Long version) {
        GraphSnapshot snapshot = version == null ? service.current() : service.atVersion(version);
        return new VertexStatsResponse(
                snapshot.version(),
                vertex,
                snapshot.degrees().getOrDefault(vertex, 0),
                snapshot.vertexTriangleCount(vertex),
                snapshot.localClusteringCoefficient(vertex));
    }

    @GetMapping("/edges/triangles")
    public EdgeStatsResponse edgeTriangles(
            @RequestParam String source,
            @RequestParam String target,
            @RequestParam(required = false) Long version) {
        service.canonicalEdge(source, target);
        GraphSnapshot snapshot = version == null ? service.current() : service.atVersion(version);
        boolean exists = snapshot.edgeTriangles()
                .containsKey(com.github.guoyyit.graphtrianglestatsjava.domain.Edge.of(source, target));
        return new EdgeStatsResponse(
                snapshot.version(),
                source,
                target,
                exists,
                snapshot.edgeTriangleCount(source, target));
    }

    @GetMapping("/triangles")
    public GraphStatsResponse graphStats(@RequestParam(required = false) Long version) {
        GraphSnapshot snapshot = version == null ? service.current() : service.atVersion(version);
        return new GraphStatsResponse(
                snapshot.version(),
                snapshot.vertexCount(),
                snapshot.edgeCount(),
                snapshot.totalTriangles(),
                snapshot.totalWedges(),
                snapshot.globalClusteringCoefficient(),
                snapshot.averageLocalClusteringCoefficient());
    }
}
