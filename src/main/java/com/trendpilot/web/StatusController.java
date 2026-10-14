package com.trendpilot.web;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trendpilot.common.CapitalAuthority;
import com.trendpilot.portfolio.ShadowBook;

/** 本机只读状态接口：http://localhost:8080/api/status */
@RestController
public class StatusController {

    private final ShadowBook book;
    private final JdbcClient jdbc;

    public StatusController(ShadowBook book, JdbcClient jdbc) {
        this.book = book;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return Map.of(
                "capitalAuthority", CapitalAuthority.CURRENT,
                "positions", book.positions(),
                "deployedCost", book.deployedCost(),
                "realizedPnl", book.realizedPnl(),
                "recentRuns", rows("SELECT job, generation, started_at, program_status, data_status, note FROM job_run ORDER BY id DESC LIMIT 20"),
                "recentEvents", rows("SELECT symbol, type, price, notional_usdt, pnl_usdt, reason_code, created_at FROM shadow_event ORDER BY id DESC LIMIT 20"),
                "recentDecisions", rows("SELECT generation, symbol, action, decision, funded, score, rr, reasons FROM decision_log ORDER BY id DESC LIMIT 30"));
    }

    private List<Map<String, Object>> rows(String sql) {
        return jdbc.sql(sql).query().listOfRows();
    }
}
