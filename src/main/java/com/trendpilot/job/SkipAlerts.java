package com.trendpilot.job;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitor 跳过持仓的告警去重：开始跳过时提醒一次，持续中每 6 小时最多提醒一次，全部恢复时提醒一次。
 * 告警按原因分组，写清是哪个交易所的哪类数据、报什么错。
 */
final class SkipAlerts {

    static final Duration REPEAT = Duration.ofHours(6);
    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private Instant since;
    private Instant lastAlert;

    /** @param why 本轮跳过的持仓 → 原因；空表示本轮没有跳过。@return 要发的告警，不用发时返回 null */
    synchronized String onRound(Map<String, String> why, Instant now) {
        if (why.isEmpty()) {
            if (since == null) return null;
            String msg = "✅ Monitor 恢复：所有持仓都拿到了盘口和交易状态（越南时间 " + VN.format(since) + " 起跳过，共 "
                    + Duration.between(since, now).toMinutes() + " 分钟）";
            since = null;
            lastAlert = null;
            return msg;
        }
        boolean first = since == null;
        if (first) since = now;
        if (!first && Duration.between(lastAlert, now).compareTo(REPEAT) < 0) return null;
        lastAlert = now;
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        why.forEach((s, r) -> byReason.computeIfAbsent(r, k -> new ArrayList<>()).add(s));
        StringBuilder b = new StringBuilder(first ? "⚠️ Monitor 跳过 " : "⚠️ Monitor 仍在跳过（越南时间 " + VN.format(since) + " 起）")
                .append(why.size()).append(" 个持仓，这些持仓本轮不做止盈和保护判断，其他持仓照常管理：");
        byReason.forEach((r, ss) -> b.append("\n· ").append(String.join("、", ss)).append("：").append(r));
        b.append("\n同一问题 6 小时内不再重复提醒，恢复时会通知。");
        return b.toString();
    }
}
