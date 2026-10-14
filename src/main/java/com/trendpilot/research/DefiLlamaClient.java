package com.trendpilot.research;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DefiLlama 公开接口（只读）：协议 TVL 与手续费 / 收入，各一次请求取全表；
 * 代币解锁取公开数据集 emissionsIndex（约 22MB，一次请求，流式解析，每天一次）。
 */
@Component
public class DefiLlamaClient {

    /** 一个协议的价值捕获数据。字段缺失时为 NaN。 */
    public record Protocol(String geckoId, String name, String category, double tvl, double fees24h,
                           double revenue24h) {
    }

    /**
     * 一个代币的解锁情况（相对流通量的百分比）。
     *
     * @param nextUnlockAt   下一次解锁时间（没有则为 null）
     * @param nextUnlockPct  下一次解锁量 / 流通量 ×100
     * @param unlock30dPct   未来 30 天预计解锁量（一次性 + 线性）/ 流通量 ×100
     */
    public record Unlock(String geckoId, java.time.Instant nextUnlockAt, double nextUnlockPct, double unlock30dPct,
                         double circulating, double maxSupply) {
    }

    static final java.time.Duration UNLOCK_WINDOW = java.time.Duration.ofDays(30);

    private final RestClient http;
    private final RestClient datasets;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public DefiLlamaClient(ResearchProperties props, RestClient.Builder builder) {
        this.http = builder.clone().baseUrl(props.defillamaBaseUrl()).build();
        this.datasets = builder.clone().baseUrl(props.defillamaDatasetsUrl()).build();
    }

    /** 全部代币的解锁情况，按 CoinGecko id 索引。数据集只有一个文件，一次请求。 */
    public Map<String, Unlock> unlocksByGeckoId(java.time.Instant now) {
        try {
            return datasets.get().uri("/emissionsIndex").exchange((req, res) -> {
                if (!res.getStatusCode().is2xxSuccessful()) {
                    throw new ResearchDataException("DefiLlama 解锁数据集返回 " + res.getStatusCode().value());
                }
                Map<String, Unlock> out = new HashMap<>();
                try (var parser = json.getFactory().createParser(res.getBody())) {
                    // 找到 data 数组，逐个条目解析，不把 22MB 整体读进内存树
                    while (parser.nextToken() != null) {
                        if (parser.currentToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME
                                && "data".equals(parser.currentName())) {
                            parser.nextToken();
                            while (parser.nextToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                                Unlock u = parseUnlock(json.readTree(parser), now);
                                if (u != null) out.putIfAbsent(u.geckoId(), u);
                            }
                            break;
                        }
                    }
                }
                return out;
            });
        } catch (RestClientException e) {
            throw new ResearchDataException("DefiLlama 解锁数据集请求失败", e);
        }
    }

    /** 解析一个条目；没有 gecko_id 或流通量时返回 null。unlockEvents 含历史事件，按时间筛未来 30 天。 */
    static Unlock parseUnlock(JsonNode e, java.time.Instant now) {
        String gid = e.path("gecko_id").asText("");
        double circ = e.path("circSupply").asDouble(0);
        if (gid.isEmpty() || gid.equals("null") || circ <= 0) return null;
        long nowSec = now.getEpochSecond();
        long endSec = now.plus(UNLOCK_WINDOW).getEpochSecond();
        double cliff = 0;
        double weeklyRate = 0;
        long rateAt = Long.MIN_VALUE;
        for (JsonNode ev : e.path("unlockEvents")) {
            long ts = ev.path("timestamp").asLong();
            JsonNode sum = ev.path("summary");
            if (ts > nowSec && ts <= endSec) cliff += sum.path("totalTokensCliff").asDouble(0);
            // 当前线性释放速率：最近一次（不晚于现在）的 totalNewWeeklyRate
            if (ts <= nowSec && ts >= rateAt && sum.has("totalNewWeeklyRate")) {
                weeklyRate = sum.path("totalNewWeeklyRate").asDouble(0);
                rateAt = ts;
            }
        }
        double linear = Math.max(0, weeklyRate) * UNLOCK_WINDOW.toDays() / 7.0;
        java.time.Instant nextAt = null;
        double nextPct = 0;
        JsonNode next = e.path("nextEvent");
        if (next.has("date") && next.path("date").asLong() > nowSec) {
            nextAt = java.time.Instant.ofEpochSecond(next.path("date").asLong());
            nextPct = next.path("toUnlock").asDouble(0) / circ * 100;
        }
        double max = e.path("maxSupply").isNumber() ? e.path("maxSupply").asDouble() : Double.NaN;
        return new Unlock(gid, nextAt, nextPct, (cliff + linear) / circ * 100, circ, max);
    }

    /** 按 CoinGecko id 索引的协议数据（同一 id 多个协议时 TVL 和费用相加）。 */
    public Map<String, Protocol> protocolsByGeckoId() {
        try {
            JsonNode protocols = http.get().uri("/protocols").retrieve().body(JsonNode.class);
            Map<String, JsonNode> fees = new HashMap<>();
            Map<String, JsonNode> revenue = new HashMap<>();
            index(http.get().uri("/overview/fees?excludeTotalDataChart=true&excludeTotalDataChartBreakdown=true")
                    .retrieve().body(JsonNode.class), fees);
            index(http.get().uri("/overview/fees?excludeTotalDataChart=true&excludeTotalDataChartBreakdown=true&dataType=dailyRevenue")
                    .retrieve().body(JsonNode.class), revenue);
            Map<String, Protocol> out = new HashMap<>();
            for (JsonNode p : protocols) {
                String gid = p.path("gecko_id").asText("");
                if (gid.isEmpty() || gid.equals("null")) continue;
                String name = p.path("name").asText();
                double tvl = p.path("tvl").isNumber() ? p.path("tvl").asDouble() : Double.NaN;
                double f = total24h(fees.get(name));
                double r = total24h(revenue.get(name));
                out.merge(gid, new Protocol(gid, name, p.path("category").asText(""), tvl, f, r),
                        (a, b) -> new Protocol(gid, a.name(), a.category(), sum(a.tvl(), b.tvl()),
                                sum(a.fees24h(), b.fees24h()), sum(a.revenue24h(), b.revenue24h())));
            }
            return out;
        } catch (RestClientException e) {
            throw new ResearchDataException("DefiLlama 请求失败", e);
        }
    }

    private static void index(JsonNode overview, Map<String, JsonNode> out) {
        if (overview == null) return;
        for (JsonNode p : overview.path("protocols")) {
            out.put(p.path("name").asText(), p);
            if (p.hasNonNull("displayName")) out.put(p.path("displayName").asText(), p);
        }
    }

    private static double total24h(JsonNode p) {
        return p != null && p.path("total24h").isNumber() ? p.path("total24h").asDouble() : Double.NaN;
    }

    private static double sum(double a, double b) {
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        return a + b;
    }
}
