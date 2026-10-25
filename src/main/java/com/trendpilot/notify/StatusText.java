package com.trendpilot.notify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.trendpilot.InstanceLock;
import com.trendpilot.config.StrategyParams;
import com.trendpilot.job.MonitorJob;
import com.trendpilot.lease.ActiveLease;
import com.trendpilot.portfolio.Ledger;

/**
 * Telegram /status 的文字：程序版本、策略版本、运行机器、最近一次成功的持仓检查、各账本持仓数。
 * 每一项单独取，取不到的那一项写"暂时取不到"，不影响其他项，也不抛异常。
 */
@Component
public class StatusText {

    static final String MISSING = "暂时取不到";
    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final JdbcClient jdbc;
    private final StrategyParams params;
    private final ObjectProvider<Ledger> books;
    private final ObjectProvider<ActiveLease> lease;
    private final Path versionFile;
    private ObjectProvider<com.trendpilot.btcdaily.BtcDailyService> btc;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBtc(ObjectProvider<com.trendpilot.btcdaily.BtcDailyService> btc) {
        this.btc = btc;
    }

    private ObjectProvider<com.trendpilot.variant.VariantRegistry> variants;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setVariants(ObjectProvider<com.trendpilot.variant.VariantRegistry> variants) {
        this.variants = variants;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StatusText(JdbcClient jdbc, StrategyParams params, ObjectProvider<Ledger> books, ObjectProvider<ActiveLease> lease) {
        this(jdbc, params, books, lease, Path.of("app", "version.txt"));
    }

    StatusText(JdbcClient jdbc, StrategyParams params, ObjectProvider<Ledger> books, ObjectProvider<ActiveLease> lease,
               Path versionFile) {
        this.jdbc = jdbc;
        this.params = params;
        this.books = books;
        this.lease = lease;
        this.versionFile = versionFile;
    }

    public String text(Instant now) {
        List<String> lines = new ArrayList<>();
        lines.add("📟 运行状态");
        lines.add("程序版本：" + safe(() -> {
            String v = Files.readString(versionFile).trim();
            return v.length() > 7 ? v.substring(0, 7) : v;
        }));
        lines.add("策略版本：" + safe(params::version));
        lines.add("运行机器：" + safe(() -> {
            ActiveLease l = lease.getIfAvailable();
            String role = l == null || !l.enabled() ? "" : (l.active() ? "（运行中）" : "（待命）");
            return InstanceLock.hostName() + role;
        }));
        lines.add("最近一次持仓检查：" + safe(() -> {
            String t = jdbc.sql("SELECT MAX(started_at) FROM job_run WHERE job = :j AND program_status = 'SUCCESS'")
                    .param("j", MonitorJob.JOB).query(String.class).optional().orElse(null);
            if (t == null || t.isBlank()) return "还没有";
            Instant at = Instant.parse(t);
            return VN.format(at) + "（" + Math.max(0, Duration.between(at, now).toMinutes()) + " 分钟前）";
        }));
        if (btc != null && btc.getIfAvailable() != null) {
            lines.add("BTC 短线卖法：" + safe(() -> btc.getObject().sellRuleShort()));
        }
        lines.add("持仓数：" + safe(() -> {
            List<String> parts = new ArrayList<>();
            books.orderedStream().forEach(b -> parts.add(b.book() + " " + b.positions().size()));
            var reg = variants == null ? null : variants.getIfAvailable();
            if (reg != null) {
                for (var v : reg.all()) {
                    if (v.name().equals(com.trendpilot.portfolio.StrongVariantBook.BOOK)) continue;
                    parts.add(v.name() + " " + v.book().positions().size());
                }
            }
            Double qty = jdbc.sql("SELECT qty FROM btc_daily_state WHERE id = 1").query(Double.class).optional().orElse(0.0);
            parts.add("BTC短线 " + (qty != null && qty > 0 ? "持仓中" : "空仓"));
            return String.join("，", parts);
        }));
        return String.join("\n", lines);
    }

    private interface Part {
        String get() throws Exception;
    }

    private static String safe(Part p) {
        try {
            String v = p.get();
            return v == null || v.isBlank() ? MISSING : v;
        } catch (Exception e) {
            return MISSING;
        }
    }
}
