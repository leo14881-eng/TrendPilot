package com.trendpilot.btcdaily;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 市场与宏观数据的免费公开来源（只读）。每个来源每天最多请求一次（由 {@link ContextService} 控制），
 * 这里再按主机保证相邻请求至少间隔 1 秒，429 时按 Retry-After 暂停该主机。失败抛 {@link ContextDataException}。
 */
@Component
public class ContextSources {

    static final long MIN_GAP_MS = 1_000;
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CELL = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("^\\d{1,2} [A-Z][a-z]{2} \\d{4}$");
    private static final DateTimeFormatter FARSIDE_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final RestClient http;
    private final BtcDailyProperties.Filters f;
    private final Map<String, Long> nextSlot = new ConcurrentHashMap<>();

    public ContextSources(BtcDailyProperties props, RestClient.Builder builder) {
        this.http = builder.clone().defaultHeader("User-Agent", "TrendPilot/1.0 (personal research)").build();
        this.f = props.filters();
    }

    /** 资金费率（%/8h），按结算时间，startMs 为 null 时取最近 limit 条。 */
    public Map<Long, Double> funding(String symbol, Long startMs, int limit) {
        String url = f.binanceFuturesUrl() + "/fapi/v1/fundingRate?symbol=" + symbol + "&limit=" + limit
                + (startMs == null ? "" : "&startTime=" + startMs);
        Map<Long, Double> out = new TreeMap<>();
        for (JsonNode n : json(url, "资金费率")) {
            out.put(n.path("fundingTime").asLong(), n.path("fundingRate").asDouble() * 100);
        }
        return out;
    }

    /** 合约持仓量（美元），按日。免费接口只有最近 30 天。 */
    public Map<LocalDate, Double> openInterest(String symbol) {
        String url = f.binanceFuturesUrl() + "/futures/data/openInterestHist?symbol=" + symbol + "&period=1d&limit=30";
        Map<LocalDate, Double> out = new TreeMap<>();
        for (JsonNode n : json(url, "持仓量")) {
            out.put(day(n.path("timestamp").asLong()), n.path("sumOpenInterestValue").asDouble());
        }
        return out;
    }

    /** 恐惧贪婪指数，按日。limit = 0 取全部历史。 */
    public Map<LocalDate, Double> fearGreed(int limit) {
        String url = f.fearGreedUrl() + "/fng/?format=json&limit=" + limit;
        Map<LocalDate, Double> out = new TreeMap<>();
        for (JsonNode n : json(url, "恐惧贪婪").path("data")) {
            out.put(day(n.path("timestamp").asLong() * 1000), n.path("value").asDouble());
        }
        if (out.isEmpty()) throw new ContextDataException("恐惧贪婪指数返回为空");
        return out;
    }

    /** BTC 现货 ETF 每日净流入（百万美元），Farside 公开页面整表（一次请求含全部历史）。 */
    public Map<LocalDate, Double> etfFlows() {
        String html = text(f.farsideUrl(), "ETF 资金流");
        Map<LocalDate, Double> out = parseFarside(html);
        if (out.isEmpty()) throw new ContextDataException("ETF 资金流页面没有解析出数据（页面结构可能变了）");
        return out;
    }

    /** FRED 序列观测值（"." 视为缺失），需要免费 key。 */
    public Map<LocalDate, Double> fredSeries(String seriesId, LocalDate from) {
        requireFred();
        String url = f.fredUrl() + "/fred/series/observations?series_id=" + seriesId + "&api_key=" + f.fredApiKey()
                + "&file_type=json&observation_start=" + from;
        Map<LocalDate, Double> out = new TreeMap<>();
        for (JsonNode n : json(url, "FRED " + seriesId).path("observations")) {
            String v = n.path("value").asText(".");
            if (!v.equals(".")) out.put(LocalDate.parse(n.path("date").asText()), Double.parseDouble(v));
        }
        return out;
    }

    /** CPI 发布日（FRED release 10，含未来已排期日期）。 */
    public Map<LocalDate, Double> cpiDates(LocalDate from) {
        requireFred();
        String url = f.fredUrl() + "/fred/release/dates?release_id=10&api_key=" + f.fredApiKey()
                + "&file_type=json&include_release_dates_with_no_data=true&sort_order=asc&limit=1000&realtime_start=" + from;
        Map<LocalDate, Double> out = new TreeMap<>();
        for (JsonNode n : json(url, "FRED CPI 日程").path("release_dates")) {
            out.put(LocalDate.parse(n.path("date").asText()), 1.0);
        }
        return out;
    }

    private void requireFred() {
        if (!f.fredConfigured()) throw new ContextDataException("未配置 FRED key");
    }

    static Map<LocalDate, Double> parseFarside(String html) {
        Map<LocalDate, Double> out = new TreeMap<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            Matcher cells = CELL.matcher(rows.group(1));
            String first = null, last = null;
            while (cells.find()) {
                String c = cells.group(1).replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
                if (first == null) first = c;
                last = c;
            }
            if (first == null || !DATE.matcher(first).matches()) continue;
            Double v = parseFlow(last);
            if (v != null) out.put(LocalDate.parse(first, FARSIDE_DATE), v);
        }
        return out;
    }

    /** "1,234.5" → 1234.5，"(123.4)" → -123.4，"-" 或空 → null（未公布）。 */
    static Double parseFlow(String s) {
        if (s == null) return null;
        String t = s.replace(",", "").trim();
        if (t.isEmpty() || t.equals("-")) return null;
        boolean neg = t.startsWith("(") && t.endsWith(")");
        if (neg) t = t.substring(1, t.length() - 1);
        try {
            double v = Double.parseDouble(t);
            return neg ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JsonNode json(String url, String what) {
        return call(url, what, () -> http.get().uri(URI.create(url)).retrieve().body(JsonNode.class));
    }

    private String text(String url, String what) {
        return call(url, what, () -> http.get().uri(URI.create(url)).retrieve().body(String.class));
    }

    private <T> T call(String url, String what, java.util.function.Supplier<T> request) {
        String host = URI.create(url).getHost();
        awaitSlot(host, what);
        try {
            T body = request.get();
            if (body == null) throw new ContextDataException(what + " 返回为空");
            return body;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 429) {
                String ra = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
                long ms = 60_000;
                try {
                    if (ra != null) ms = Long.parseLong(ra.trim()) * 1000;
                } catch (NumberFormatException ignored) {
                    // 按 60 秒
                }
                nextSlot.merge(host, System.currentTimeMillis() + ms, Math::max);
            }
            throw new ContextDataException(what + " 请求失败 " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new ContextDataException(what + " 请求失败：" + e.getMessage(), e);
        }
    }

    /** 同一主机相邻请求至少间隔 1 秒；处于 429 暂停期时直接失败，不等待。 */
    private void awaitSlot(String host, String what) {
        long wait;
        synchronized (nextSlot) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextSlot.getOrDefault(host, 0L));
            if (slot - now > 30_000) throw new ContextDataException(what + "：" + host + " 限流暂停中");
            nextSlot.put(host, slot + MIN_GAP_MS);
            wait = slot - now;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContextDataException("请求被中断：" + what, e);
            }
        }
    }

    static LocalDate day(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
