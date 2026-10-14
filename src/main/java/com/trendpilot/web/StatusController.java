package com.trendpilot.web;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trendpilot.common.CapitalAuthority;
import com.trendpilot.market.stream.StreamHub;
import com.trendpilot.portfolio.ObservationBook;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.replay.BlindReplay;

/** 本机只读状态接口：http://localhost:8080/api/status */
@RestController
public class StatusController {

    private final ShadowBook book;
    private final ObservationBook v1;
    private final JdbcClient jdbc;
    private final StreamHub streams;
    private final BlindReplay blind;

    public StatusController(ShadowBook book, ObservationBook v1, JdbcClient jdbc, StreamHub streams,
                            BlindReplay blind) {
        this.blind = blind;
        this.book = book;
        this.v1 = v1;
        this.streams = streams;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return Map.ofEntries(
                Map.entry("capitalAuthority", CapitalAuthority.CURRENT),
                Map.entry("positions", book.positions()),
                Map.entry("v1Positions", v1.positions()),
                Map.entry("comparison", List.of(book.stats(), v1.stats())),
                Map.entry("websocket", streams.health()),
                Map.entry("deployedCost", book.deployedCost()),
                Map.entry("realizedPnl", book.realizedPnl()),
                Map.entry("blindReplay7d", blind.summary(java.time.Instant.now(), 7)),
                Map.entry("recentRuns", rows("SELECT job, generation, started_at, program_status, data_status, note FROM job_run ORDER BY id DESC LIMIT 20")),
                Map.entry("recentEvents", rows("SELECT book, venue, symbol, type, price, notional_usdt, pnl_usdt, reason_code, created_at FROM shadow_event ORDER BY id DESC LIMIT 20")),
                Map.entry("recentDecisions", rows("SELECT generation, venue, symbol, action, decision, funded, score, rr, reasons FROM decision_log ORDER BY id DESC LIMIT 30")),
                Map.entry("missedReplay", rows("SELECT day, venue, symbol, change_pct, first_early_at, outcome, reasons FROM missed_replay ORDER BY day DESC, change_pct DESC LIMIT 20")));
    }

    private List<Map<String, Object>> rows(String sql) {
        return jdbc.sql(sql).query().listOfRows();
    }
}
