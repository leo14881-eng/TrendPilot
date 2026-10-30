package com.trendpilot.lpshadow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 一条链的公开 JSON-RPC（只读）。只允许 eth_call 和 eth_gasPrice 两个方法，其他方法在发出前就拒绝。
 * <ul>
 * <li>每次把多个调用合成一个 batch 请求；</li>
 * <li>每条链一个限流器：相邻请求至少间隔 minGapMs；429 或 RPC 返回限流时按 Retry-After（没有就 2s、4s、8s… 封顶 5 分钟）暂停；</li>
 * <li>暂停期间不排队等待，直接失败，由调用方把这一分钟记为 gap。</li>
 * </ul>
 */
final class RpcClient {

    static final Set<String> ALLOWED_METHODS = Set.of("eth_call", "eth_gasPrice");
    static final long MAX_BACKOFF_MS = 300_000;

    /** 一个调用；result 是十六进制字符串，error 非空表示这一项失败。 */
    record Call(String method, String to, String data) {
        static Call ethCall(String to, String data) {
            return new Call("eth_call", to, data);
        }

        static Call gasPrice() {
            return new Call("eth_gasPrice", null, null);
        }
    }

    record Result(String value, String error) {
        boolean ok() {
            return error == null && value != null && value.startsWith("0x") && value.length() > 2;
        }
    }

    /** HTTP 层，测试里替换成假的。 */
    interface Transport {
        JsonNode post(String body);
    }

    static final class RpcException extends RuntimeException {
        RpcException(String message) {
            super(message);
        }

        RpcException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 限流（HTTP 429 或 RPC 返回限流错误码）。 */
    static final class RateLimited extends RuntimeException {
        final long retryAfterMs;

        RateLimited(long retryAfterMs) {
            super("rate limited");
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final Transport transport;
    private final long minGapMs;
    private final LongSupplier clock;
    private long lastSentMs = Long.MIN_VALUE / 2;
    private long pausedUntilMs;
    private int consecutiveLimited;

    RpcClient(String name, Transport transport, long minGapMs, LongSupplier clock) {
        this.name = name;
        this.transport = transport;
        this.minGapMs = minGapMs;
        this.clock = clock;
    }

    static Transport http(RestClient.Builder builder, String url) {
        RestClient http = builder.clone()
                .requestFactory(com.trendpilot.health.ApiStatsConfig.requestFactory(5_000, 10_000))
                .defaultHeader("User-Agent", "TrendPilot/1.0 (read-only research)")
                .build();
        return body -> {
            try {
                return http.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode().value() == 429) {
                    String v = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
                    long ms = -1;
                    try {
                        if (v != null) ms = Math.max(1, Long.parseLong(v.trim())) * 1000L;
                    } catch (NumberFormatException ignored) {
                        // 按指数退避
                    }
                    throw new RateLimited(ms);
                }
                throw new RpcException("HTTP " + e.getStatusCode().value(), e);
            } catch (RuntimeException e) {
                if (e instanceof RateLimited || e instanceof RpcException) throw e;
                throw new RpcException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
        };
    }

    String name() {
        return name;
    }

    synchronized long pausedUntilMs() {
        return pausedUntilMs;
    }

    /** 发一个 batch；返回值和 calls 一一对应。整个请求失败时抛 {@link RpcException}。 */
    List<Result> batch(List<Call> calls) {
        if (calls.isEmpty()) return List.of();
        for (Call c : calls) {
            if (!ALLOWED_METHODS.contains(c.method())) throw new IllegalArgumentException("只读模块不允许调用 " + c.method());
        }
        long wait;
        synchronized (this) {
            long now = clock.getAsLong();
            if (now < pausedUntilMs) throw new RpcException(name + " 限流暂停中，" + (pausedUntilMs - now) / 1000 + " 秒后恢复");
            wait = Math.max(0, lastSentMs + minGapMs - now);
            lastSentMs = now + wait;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RpcException(name + " 请求被中断");
            }
        }
        ArrayNode req = JSON.createArrayNode();
        for (int i = 0; i < calls.size(); i++) {
            Call c = calls.get(i);
            ObjectNode n = req.addObject().put("jsonrpc", "2.0").put("id", i).put("method", c.method());
            ArrayNode params = n.putArray("params");
            if (c.method().equals("eth_call")) {
                params.addObject().put("to", c.to()).put("data", c.data());
                params.add("latest");
            }
        }
        JsonNode body;
        try {
            body = transport.post(req.toString());
        } catch (RateLimited e) {
            limited(e.retryAfterMs);
            throw new RpcException(name + " 限流 429");
        }
        if (body == null || !body.isArray()) {
            if (body != null && isRateLimit(body.path("error"))) {
                limited(-1);
                throw new RpcException(name + " 限流（RPC 错误）");
            }
            throw new RpcException(name + " 返回的不是 batch 数组：" + abbreviate(String.valueOf(body)));
        }
        Map<Integer, Result> byId = new HashMap<>();
        boolean anyLimited = false;
        for (JsonNode r : body) {
            int id = r.path("id").asInt(-1);
            JsonNode err = r.get("error");
            if (err != null && !err.isNull()) {
                anyLimited |= isRateLimit(err);
                byId.put(id, new Result(null, abbreviate(err.toString())));
            } else {
                byId.put(id, new Result(r.path("result").asText(null), null));
            }
        }
        if (anyLimited) limited(-1);
        else ok();
        List<Result> out = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) out.add(byId.getOrDefault(i, new Result(null, "missing")));
        return out;
    }

    private static boolean isRateLimit(JsonNode err) {
        if (err == null || err.isMissingNode() || err.isNull()) return false;
        int code = err.path("code").asInt();
        String msg = err.path("message").asText("").toLowerCase(java.util.Locale.ROOT);
        return code == 429 || code == -32005 || code == -32029 || msg.contains("rate limit") || msg.contains("too many");
    }

    private synchronized void limited(long retryAfterMs) {
        consecutiveLimited++;
        long ms = retryAfterMs > 0 ? retryAfterMs : 2_000L << Math.min(consecutiveLimited - 1, 10);
        pausedUntilMs = Math.max(pausedUntilMs, clock.getAsLong() + Math.min(MAX_BACKOFF_MS, ms));
    }

    private synchronized void ok() {
        consecutiveLimited = 0;
    }

    static String abbreviate(String s) {
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
