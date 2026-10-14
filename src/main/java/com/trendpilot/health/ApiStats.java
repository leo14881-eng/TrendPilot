package com.trendpilot.health;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLongArray;

import org.springframework.stereotype.Component;

/**
 * 对外接口请求统计（只在内存里，按主机、按小时分桶，保留约 26 小时）。
 * 每次 HTTP 尝试都算一次（含重试），所以能看出真实请求量。只记主机名，不记路径（Telegram 的路径里有 token）。
 */
@Component
public class ApiStats {

    static final int OK = 0, LIMITED = 1, ERROR = 2;
    private static final long KEEP_HOURS = 26;

    /** 一段时间内某个主机的请求次数、被限流（429/418）次数、其他失败次数。 */
    public record Counts(long requests, long limited, long errors) {
    }

    private final ConcurrentMap<String, ConcurrentMap<Long, AtomicLongArray>> byHost = new ConcurrentHashMap<>();

    /** @param status HTTP 状态码；网络错误（没拿到响应）传 -1 */
    public void record(String host, int status, Instant now) {
        long hour = now.getEpochSecond() / 3600;
        ConcurrentMap<Long, AtomicLongArray> buckets = byHost.computeIfAbsent(host, h -> new ConcurrentHashMap<>());
        buckets.computeIfAbsent(hour, h -> new AtomicLongArray(3))
                .incrementAndGet(status == 429 || status == 418 ? LIMITED : status >= 200 && status < 400 ? OK : ERROR);
        buckets.keySet().removeIf(h -> h < hour - KEEP_HOURS);
    }

    /** 最近 window（按整小时向上取）内每个主机的统计，按主机名排序。 */
    public Map<String, Counts> since(Duration window, Instant now) {
        long to = now.getEpochSecond() / 3600;
        long from = to - Math.max(1, (window.toSeconds() + 3599) / 3600) + 1;
        Map<String, Counts> out = new TreeMap<>();
        byHost.forEach((host, buckets) -> {
            long ok = 0, limited = 0, errors = 0;
            for (var e : buckets.entrySet()) {
                if (e.getKey() < from || e.getKey() > to) continue;
                ok += e.getValue().get(OK);
                limited += e.getValue().get(LIMITED);
                errors += e.getValue().get(ERROR);
            }
            if (ok + limited + errors > 0) out.put(host, new Counts(ok + limited + errors, limited, errors));
        });
        return out;
    }
}
