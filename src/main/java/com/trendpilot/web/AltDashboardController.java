package com.trendpilot.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trendpilot.altseason.AltDashboard;

/** http://localhost:8080/api/alt-dashboard：最近一份山寨季仪表盘（只读信息，每天越南 07:07 左右生成）。 */
@RestController
public class AltDashboardController {

    private final AltDashboard dashboard;

    public AltDashboardController(AltDashboard dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/alt-dashboard")
    public ResponseEntity<String> latest() {
        return ResponseEntity.ok().contentType(new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8))
                .body(dashboard.latest().orElse("还没有生成过山寨季仪表盘（每天越南 07:07 左右生成）\n"));
    }
}
