package com.trendpilot.research;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

/** DefiLlama 公开接口（只读）：协议 TVL 与手续费 / 收入，各一次请求取全表。 */
@Component
public class DefiLlamaClient {

    /** 一个协议的价值捕获数据。字段缺失时为 NaN。 */
    public record Protocol(String geckoId, String name, String category, double tvl, double fees24h,
                           double revenue24h) {
    }

    private final RestClient http;

    public DefiLlamaClient(ResearchProperties props, RestClient.Builder builder) {
        this.http = builder.clone().baseUrl(props.defillamaBaseUrl()).build();
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
