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
    private final com.trendpilot.notify.Notifier notifier;

    public AdminController(ConfigurableApplicationContext context, com.trendpilot.notify.Notifier notifier) {
        this.context = context;
        this.notifier = notifier;
    }

    /** 每周策略分析的结论通过本机服务发 Telegram（调用方不需要也看不到 Token）。key 用于去重。 */
    @PostMapping("/api/admin/notify")
    public ResponseEntity<Map<String, Object>> notify(@RequestHeader(value = HEADER, required = false) String token,
                                                      @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        if (!"notify".equals(token)) return ResponseEntity.status(403).body(Map.of("status", "FORBIDDEN"));
        String text = body.getOrDefault("text", "").strip();
        if (text.isEmpty() || text.length() > 3500) return ResponseEntity.badRequest().body(Map.of("status", "BAD_TEXT"));
        String key = "weekly:" + body.getOrDefault("key", Integer.toHexString(text.hashCode()));
        return ResponseEntity.ok(Map.of("sent", notifier.send(key, text)));
    }

    @PostMapping("/api/admin/shutdown")
    public ResponseEntity<Map<String, String>> shutdown(@RequestHeader(value = HEADER, required = false) String token) {
        if (!"shutdown".equals(token)) return ResponseEntity.status(403).body(Map.of("status", "FORBIDDEN"));
        log.info("收到平滑停机请求（自动更新）");
        Thread.ofPlatform().name("shutdown").start(() -> System.exit(SpringApplication.exit(context, () -> 0)));
        return ResponseEntity.accepted().body(Map.of("status", "STOPPING"));
    }
}
