package com.trendpilot.btcdaily;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** http://localhost:8080/api/btc-daily：当天计划、持仓、累计、最近成交、回测结果。 */
@RestController
public class BtcDailyController {

    private final BtcDailyService service;

    public BtcDailyController(BtcDailyService service) {
        this.service = service;
    }

    @GetMapping("/api/btc-daily")
    public Map<String, Object> status() {
        return service.summary(Instant.now());
    }
}
