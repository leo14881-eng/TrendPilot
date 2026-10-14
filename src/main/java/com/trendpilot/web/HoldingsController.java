package com.trendpilot.web;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.trendpilot.report.HoldingsReport;

/** http://localhost:8080/api/holdings：当天到现在的 V2 日报；/api/v2-daily?day=yyyy-MM-dd：某天的日报（Markdown，不含密钥）。 */
@RestController
public class HoldingsController {

    private final HoldingsReport report;

    public HoldingsController(HoldingsReport report) {
        this.report = report;
    }

    @GetMapping("/api/holdings")
    public ResponseEntity<String> holdings() {
        return ResponseEntity.ok().contentType(new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8))
                .body(report.markdown(Instant.now()));
    }

    /** 某天的 V2 日报：已保存（每晚 00:00 生成）就返回保存的版本，否则按现在的状态现算。 */
    @GetMapping("/api/v2-daily")
    public ResponseEntity<String> daily(@RequestParam(required = false) String day) {
        Instant now = Instant.now();
        LocalDate d = day == null ? null : LocalDate.parse(day);
        String body = d == null ? report.markdown(now) : report.saved(d).orElseGet(() -> report.markdown(d, now));
        return ResponseEntity.ok().contentType(new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }
}
