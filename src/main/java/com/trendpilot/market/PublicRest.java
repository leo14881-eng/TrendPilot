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
 * <li>429 / 418 / 交易所限流码：读 Retry-After（没有则 2s、4s、8s… 封顶 2 分钟），期间该交易所全部请求暂停；
 *     暂停超过 30 秒时本次直接失败（标记缺失），不让任务长时间卡住；</li>
 * <li>5xx 和网络错误有界重试；418（封禁）和其他 4xx（如 Bybit 地区限制 403）立即失败。</li>
 * </ul>
 */
final class PublicRest {

    private static final Logger log = LoggerFactory.getLogger(PublicRest.class);
    private static final int MAX_ATTEMPTS = 3;

    private static final long MAX_PAUSE_MS = 120_000;
    private static final long MAX_WAIT_MS = 30_000;

    private final String name;
    private final Semaphore permits;
    private final long minGapMs;
    private long nextSlotMs;
    private volatile long pausedUntilMs;

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
                throw new MarketDataException(name + " 限流暂停中，" + (slot - now) / 1000 + " 秒后恢复：" + what);
            }
            nextSlotMs = slot + minGapMs;
            wait = slot - now;
        }
        if (wait > 0) Thread.sleep(wait);
    }

    private void pause(long ms) {
        pausedUntilMs = Math.max(pausedUntilMs, System.currentTimeMillis() + Math.min(MAX_PAUSE_MS, ms));
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
                    return check.apply(request.get().retrieve().body(JsonNode.class));
                } finally {
                    permits.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MarketDataException("请求被中断：" + name + " " + what, e);
            } catch (HttpStatusCodeException e) {
                HttpStatusCode code = e.getStatusCode();
                if (code.value() == 429 || code.value() == 418) {
                    long ms = retryAfterMs(e, 2_000L << (attempt - 1));
                    pause(ms);
                    log.warn("{} 限流 {}，全部请求暂停 {} 秒：{}", name, code.value(), Math.min(MAX_PAUSE_MS, ms) / 1000, what);
                }
                if (code.value() == 418 || (code.is4xxClientError() && code.value() != 429)) {
                    throw new MarketDataException(name + " 拒绝请求 " + code.value() + "：" + what, e);
                }
                last = e;
            } catch (ResourceAccessException e) {
                last = e;
            } catch (RetryableException e) {
                last = e;
                pause(2_000L << (attempt - 1));
            }
            if (attempt < MAX_ATTEMPTS) {
                log.warn("{} 请求失败，第 {} 次重试：{}", name, attempt, what);
                sleep(Duration.ofMillis(500L << (attempt - 1)));
            }
        }
        throw new MarketDataException(name + " 请求多次失败：" + what, last);
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
