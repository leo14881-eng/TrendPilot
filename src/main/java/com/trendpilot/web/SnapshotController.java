package com.trendpilot.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trendpilot.job.SnapshotJob;

/** http://localhost:8080/api/snapshot：当前统计快照（每周策略分析用，不含密钥）。 */
@RestController
public class SnapshotController {

    private final SnapshotJob snapshots;

    public SnapshotController(SnapshotJob snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping("/api/snapshot")
    public Map<String, Object> snapshot() {
        return snapshots.snapshot(Instant.now());
    }
}
