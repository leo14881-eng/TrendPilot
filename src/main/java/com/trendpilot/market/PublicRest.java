package com.trendpilot.market;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 公开行情 REST 请求，每个交易所一个统一限速器：
 * <ul>
 * <li>全局最多 maxConcurrency 个并发，且相邻两次请求至少间隔 minGapMs（排队发出）；</li>
 * <li>429 / 交易所限流码：读 Retry-After（没有则 2s、4s、8s… 封顶 2 分钟），期间该交易所全部请求暂停；</li>
 * <li>418（IP 被封禁，可能几分钟到几天）：按 Retry-After 全额暂停，不设上限（没有 Retry-After 按 10 分钟），
 *     封禁期间继续请求只会延长封禁；</li>
 * <li>暂停超过 30 秒时本次直接失败（标记缺失），不让任务长时间卡住；</li>
 * <li>5xx 和网络错误有界重试；连续失败 5 次（交易所整体不可用）熔断 60 秒，期间请求直接失败，
 *     不会对几百个标的逐个重试；其他 4xx（如 Bybit 地区限制 403）立即失败。</li>
 * </ul>
 */
final class PublicRest {

    private static final Logger log = LoggerFactory.getLogger(PublicRest.class);
    private static final int MAX_ATTEMPTS = 3;

    private static final long MAX_PAUSE_MS = 120_000;
    private static final long MAX_WAIT_MS = 30_000;
    private static final long BAN_DEFAULT_MS = 600_000;
    static final int BREAKER_FAILURES = 5;
    static final long BREAKER_MS = 60_000;

    private final String name;
    private final Semaphore permits;
    private final long minGapMs;
    private long nextSlotMs;
    private volatile long pausedUntilMs;
    /** 当前暂停的原因，写进失败信息：交易所限流、IP 封禁和网络连续不通分开说明，便于判断是不是请求太多。 */
    private volatile String pauseLabel = "限流暂停中";
    private int consecutiveFailures;

    PublicRest(String name, int maxConcurrency, long minGapMs) {
        this.name = name;
        this.permits = new Semaphore(Math.max(1, maxConcurrency));
        this.minGapMs = minGapMs;
    }

    /** 排队：取得下一个发送时间点并等待到那一刻；处在限流暂停期且要等太久时直接失败。 */
    private void awaitSlot(String what) throws InterruptedException {
        long wait;
        synchronized (this) {
            long now = System.currentTimeMillis();
            long slot = Math.max(Math.max(now, nextSlotMs), pausedUntilMs);
            if (slot - now > MAX_WAIT_MS) {
                throw new MarketDataException(name + " " + pauseLabel + "，" + (slot - now) / 1000 + " 秒后恢复：" + what);
            }
            nextSlotMs = slot + minGapMs;
            wait = slot - now;
        }
        if (wait > 0) Thread.sleep(wait);
    }

    private void pause(long ms) {
        pause(ms, "限流暂停中");
    }

    private void pause(long ms, String label) {
        pauseLabel = label;
        pausedUntilMs = Math.max(pausedUntilMs, System.currentTimeMillis() + Math.min(MAX_PAUSE_MS, ms));
    }

    /** 不设上限的暂停（418 封禁）。 */
    private void ban(long ms) {
        pauseLabel = "IP 被封禁暂停中";
        pausedUntilMs = Math.max(pausedUntilMs, System.currentTimeMillis() + ms);
    }

    private synchronized void succeeded() {
        consecutiveFailures = 0;
    }

    /** 网络错误或 5xx：连续失败太多次说明交易所整体不可用，熔断一段时间。 */
    private synchronized void failed(String what) {
        if (++consecutiveFailures >= BREAKER_FAILURES) {
            consecutiveFailures = 0;
            pause(BREAKER_MS, "网络或交易所连续不通，暂停中（不是限流）");
            log.warn("{} 连续失败 {} 次，熔断 {} 秒：{}", name, BREAKER_FAILURES, BREAKER_MS / 1000, what);
        }
    }

    private static long retryAfterMs(HttpStatusCodeException e, long fallback) {
        String v = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        try {
            return v == null ? fallback : Math.max(1, Long.parseLong(v.trim())) * 1000L;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    JsonNode get(Supplier<RestClient.RequestHeadersSpec<?>> request, String what) {
        return get(request, what, body -> body);
    }

    /** check 检查响应体：可重试的错误抛 {@link RetryableException}，其他错误抛 {@link MarketDataException}。 */
    JsonNode get(Supplier<RestClient.RequestHeadersSpec<?>> request, String what, UnaryOperator<JsonNode> check) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                awaitSlot(what);
                permits.acquire();
                try {
                    JsonNode body = check.apply(request.get().retrieve().body(JsonNode.class));
                    succeeded();
                    return body;
                } finally {
                    permits.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MarketDataException("请求被中断：" + name + " " + what, e);
            } catch (HttpStatusCodeException e) {
                HttpStatusCode code = e.getStatusCode();
                if (code.value() == 418) {
                    long ms = retryAfterMs(e, BAN_DEFAULT_MS);
                    ban(ms);
                    log.error("{} IP 被封禁（418），全部请求暂停 {} 秒：{}", name, ms / 1000, what);
                    throw new MarketDataException(name + " IP 被封禁 418：" + what, e);
                }
                if (code.value() == 429) {
                    long ms = retryAfterMs(e, 2_000L << (attempt - 1));
                    pause(ms);
                    log.warn("{} 限流 429，全部请求暂停 {} 秒：{}", name, Math.min(MAX_PAUSE_MS, ms) / 1000, what);
                } else if (code.is4xxClientError()) {
                    throw new MarketDataException(name + " 拒绝请求 " + code.value() + "：" + what, e);
                } else {
                    failed(what);
                }
                last = e;
            } catch (ResourceAccessException e) {
                failed(what);
                last = e;
            } catch (RetryableException e) {
                last = e;
                pause(2_000L << (attempt - 1));
            }
            if (attempt < MAX_ATTEMPTS) {
                log.warn("{} 请求失败（{}），第 {} 次重试：{}", name, reason(last), attempt, what);
                sleep(Duration.ofMillis(500L << (attempt - 1)));
            }
        }
        throw new MarketDataException(name + " 请求多次失败（" + reason(last) + "）：" + what, last);
    }

    /** 失败原因的简短说明：HTTP 状态码，或网络错误的根因类型和信息，便于事后定位。 */
    static String reason(Exception e) {
        if (e == null) return "未知";
        if (e instanceof HttpStatusCodeException h) return "HTTP " + h.getStatusCode().value();
        if (e instanceof RetryableException) return "交易所返回限流：" + e.getMessage();
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage();
        String type = root.getClass().getSimpleName();
        if (root instanceof java.nio.channels.UnresolvedAddressException || root instanceof java.net.UnknownHostException) {
            return "网络不通（域名解析失败）" + (msg == null || msg.isBlank() ? "" : "：" + msg);
        }
        if (msg == null || msg.isBlank()) return type;
        msg = msg.length() > 120 ? msg.substring(0, 120) : msg;
        return type + "：" + msg;
    }

    /** 交易所在 200 响应里返回的限流等可重试错误。 */
    static final class RetryableException extends RuntimeException {
        RetryableException(String message) {
            super(message);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
