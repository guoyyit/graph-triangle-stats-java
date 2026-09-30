package com.github.guoyyit.graphtrianglestatsjava.web;

import com.github.guoyyit.graphtrianglestatsjava.service.GraphStatsService;
import com.github.guoyyit.graphtrianglestatsjava.stats.EdgeStats;
import com.github.guoyyit.graphtrianglestatsjava.stats.GlobalStats;
import com.github.guoyyit.graphtrianglestatsjava.stats.NodeStats;
import com.github.guoyyit.graphtrianglestatsjava.store.BatchResult;
import com.github.guoyyit.graphtrianglestatsjava.web.dto.SubmitBatchRequest;
import com.github.guoyyit.graphtrianglestatsjava.web.dto.SubmitBatchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/graph")
public class GraphStatsController {

    private final GraphStatsService service;

    public GraphStatsController(GraphStatsService service) {
        this.service = service;
    }

    @PostMapping("/events")
    public SubmitBatchResponse submit(@RequestBody SubmitBatchRequest request) {
        BatchResult result = service.submitBatch(request.events());
        return new SubmitBatchResponse(result.version(), result.appliedEvents(), result.ignoredEvents());
    }

    @GetMapping("/nodes/{node}")
    public NodeStats node(@PathVariable long node) {
        return service.nodeStats(node);
    }

    @GetMapping("/edges")
    public EdgeStats edge(@RequestParam long u, @RequestParam long v) {
        return service.edgeStats(u, v);
    }

    @GetMapping("/global")
    public GlobalStats global() {
        return service.globalStats();
    }
}
