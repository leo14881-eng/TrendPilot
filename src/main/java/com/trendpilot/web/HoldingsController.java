package com.trendpilot.web;

import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trendpilot.report.HoldingsReport;

/** http://localhost:8080/api/holdings：当前持仓明细（Markdown，不含密钥）。 */
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
}
