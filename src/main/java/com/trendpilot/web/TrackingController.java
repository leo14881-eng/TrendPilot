package com.trendpilot.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trendpilot.tracking.ExitReplay;
import com.trendpilot.tracking.TrackingReport;
import com.trendpilot.variant.ParallelExits;

/** 信号跟踪与退出规则研究的只读接口。 */
@RestController
public class TrackingController {

    private final TrackingReport report;
    private final ExitReplay replay;
    private final ParallelExits parallel;

    public TrackingController(TrackingReport report, ExitReplay replay, ParallelExits parallel) {
        this.report = report;
        this.replay = replay;
        this.parallel = parallel;
    }

    /** http://localhost:8080/api/tracking/summary */
    @GetMapping("/api/tracking/summary")
    public TrackingReport.Summary summary() {
        return report.summary(Instant.now());
    }

    /** http://localhost:8080/api/tracking/rules：最近一次离线回放的各规则指标 + 影子并行对比 */
    @GetMapping("/api/tracking/rules")
    public Map<String, Object> rules() {
        List<ExitReplay.RuleStats> stats = replay.stats(replay.latest());
        return Map.of("replay", stats, "replayText", replay.lines(stats, 5), "parallel", parallel.compare());
    }
}
