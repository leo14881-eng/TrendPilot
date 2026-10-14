package com.trendpilot.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自动更新脚本用的平滑停机：等正在跑的任务结束后退出。服务只监听 127.0.0.1；
 * 另外要求自定义请求头，浏览器网页无法跨站触发。
 */
@RestController
public class AdminController {

    static final String HEADER = "X-TrendPilot-Admin";
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ConfigurableApplicationContext context;

    public AdminController(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @PostMapping("/api/admin/shutdown")
    public ResponseEntity<Map<String, String>> shutdown(@RequestHeader(value = HEADER, required = false) String token) {
        if (!"shutdown".equals(token)) return ResponseEntity.status(403).body(Map.of("status", "FORBIDDEN"));
        log.info("收到平滑停机请求（自动更新）");
        Thread.ofPlatform().name("shutdown").start(() -> System.exit(SpringApplication.exit(context, () -> 0)));
        return ResponseEntity.accepted().body(Map.of("status", "STOPPING"));
    }
}
