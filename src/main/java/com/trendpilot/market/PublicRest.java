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
 * 公开行情 REST 请求：全局最多 maxConcurrency 个并发；429/5xx/网络错误有界重试 + 指数退避；
 * 418（封禁）和其他 4xx（如 Bybit 地区限制 403）立即失败，不靠无限重试清掉拒绝状态。
 */
final class PublicRest {

    private static final Logger log = LoggerFactory.getLogger(PublicRest.class);
    private static final int MAX_ATTEMPTS = 3;

    private final String name;
    private final Semaphore permits;

    PublicRest(String name, int maxConcurrency) {
        this.name = name;
        this.permits = new Semaphore(Math.max(1, maxConcurrency));
    }

    JsonNode get(Supplier<RestClient.RequestHeadersSpec<?>> request, String what) {
        return get(request, what, body -> body);
    }

    /** check 检查响应体：可重试的错误抛 {@link RetryableException}，其他错误抛 {@link MarketDataException}。 */
    JsonNode get(Supplier<RestClient.RequestHeadersSpec<?>> request, String what, UnaryOperator<JsonNode> check) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
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
                if (code.value() == 418 || (code.is4xxClientError() && code.value() != 429)) {
                    throw new MarketDataException(name + " 拒绝请求 " + code.value() + "：" + what, e);
                }
                last = e;
            } catch (ResourceAccessException e) {
                last = e;
            } catch (RetryableException e) {
                last = e;
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
