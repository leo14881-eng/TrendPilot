package com.trendpilot.research;

import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DefiLlama 免费稳定币接口 {@code stablecoincharts/all}：全部稳定币每天的总供应（所有锚定币种合计美元值），一次请求取全部历史。
 * 只给山寨季仪表盘的"稳定币 30 日增速"用；调用方每天最多请求 1～2 次并存 SQLite。失败或限流直接抛出，不在这里重试
 * （429 时调用方当天不再请求，显示"-"）。
 */
@Component
public class StablecoinClient {

    private final RestClient http;

    public StablecoinClient(RestClient.Builder builder,
                            @Value("${trendpilot.altseason.stablecoins-url:https://stablecoins.llama.fi}") String baseUrl) {
        this.http = builder.clone().baseUrl(baseUrl).build();
    }

    /** UTC epochDay → 总供应（美元）。 */
    public Map<Long, Double> totals() {
        try {
            return parse(http.get().uri("/stablecoincharts/all").retrieve().body(JsonNode.class));
        } catch (RestClientResponseException e) {
            throw new ResearchDataException("DefiLlama 稳定币接口返回 " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new ResearchDataException("DefiLlama 稳定币接口请求失败", e);
        }
    }

    /** 每个点的 totalCirculatingUSD 各币种相加；缺字段或合计不为正的点跳过。 */
    static Map<Long, Double> parse(JsonNode root) {
        Map<Long, Double> out = new TreeMap<>();
        if (root == null || !root.isArray()) throw new ResearchDataException("DefiLlama 稳定币接口格式不对");
        for (JsonNode n : root) {
            JsonNode t = n.get("totalCirculatingUSD");
            JsonNode date = n.get("date");
            if (t == null || date == null) continue;
            long day;
            try {
                day = Long.parseLong(date.asText()) / 86_400;
            } catch (NumberFormatException e) {
                continue;
            }
            double s = 0;
            for (JsonNode v : t) s += v.asDouble();
            if (s > 0 && Double.isFinite(s)) out.put(day, s);
        }
        return out;
    }
}
