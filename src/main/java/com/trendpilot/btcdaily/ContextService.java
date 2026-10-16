package com.trendpilot.btcdaily;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 市场与宏观数据：按来源存 SQLite（btc_daily_series），实盘和回测都从表里组装 {@link MarketContext}，口径一致。
 * 每个来源成功后 20 小时内不再请求（CPI 日程 7 天），失败后 6 小时内不重试；一个来源失败只记缺失。
 * 只用计划生成时已公布的数据：资金费率、恐惧贪婪用前一天；ETF、FRED 用两天前及更早（公布有延迟）。
 */
@Service
public class ContextService {

    private static final Logger log = LoggerFactory.getLogger(ContextService.class);

    static final String FUNDING = "funding", FNG = "fng", ETF = "etf", US10Y = "us10y", DOLLAR = "dollar",
            CPI = "cpi", OI = "oi";
    static final Duration FRESH = Duration.ofHours(20), CPI_FRESH = Duration.ofDays(7), BACKOFF = Duration.ofHours(6);

    private final ContextSources sources;
    private final JdbcClient jdbc;
    private final BtcDailyProperties props;

    public ContextService(ContextSources sources, JdbcClient jdbc, BtcDailyProperties props) {
        this.sources = sources;
        this.jdbc = jdbc;
        this.props = props;
    }

    /** 实盘：刷新到期的来源（每个来源一次请求）。 */
    public void refreshDue(Instant now) {
        if (!props.filters().enabled()) return;
        LocalDate today = ContextSources.day(now.toEpochMilli());
        String sym = props.symbol();
        refresh(FUNDING, now, FRESH, () -> dailyFunding(sources.funding(sym, null, 30)));
        refresh(FNG, now, FRESH, () -> sources.fearGreed(30));
        refresh(ETF, now, FRESH, sources::etfFlows);
        refresh(OI, now, FRESH, () -> sources.openInterest(sym));
        if (props.filters().fredConfigured()) {
            refresh(US10Y, now, FRESH, () -> sources.fredSeries("DGS10", today.minusDays(60)));
            refresh(DOLLAR, now, FRESH, () -> sources.fredSeries("DTWEXBGS", today.minusDays(60)));
            refresh(CPI, now, CPI_FRESH, () -> sources.cpiDates(today.minusDays(30)));
        }
    }

    /** 回测：一次性取回 from 起的历史（资金费率分页，其余各一次请求）。失败的来源在回测里视为缺失。 */
    public void loadHistory(LocalDate from, Instant now) {
        if (!props.filters().enabled()) return;
        String sym = props.symbol();
        refresh(FUNDING, now, Duration.ZERO, () -> {
            Map<Long, Double> all = new TreeMap<>();
            long start = BtcDailyRules.dayStart(from);
            for (int page = 0; page < 10; page++) {
                Map<Long, Double> p = sources.funding(sym, start, 1000);
                all.putAll(p);
                if (p.size() < 1000) break;
                start = ((TreeMap<Long, Double>) p).lastKey() + 1;
            }
            return dailyFunding(all);
        });
        refresh(FNG, now, Duration.ZERO, () -> sources.fearGreed(0));
        refresh(ETF, now, FRESH, sources::etfFlows);
        if (props.filters().fredConfigured()) {
            refresh(US10Y, now, Duration.ZERO, () -> sources.fredSeries("DGS10", from.minusDays(20)));
            refresh(DOLLAR, now, Duration.ZERO, () -> sources.fredSeries("DTWEXBGS", from.minusDays(20)));
            refresh(CPI, now, Duration.ZERO, () -> sources.cpiDates(from));
        }
    }

    private void refresh(String source, Instant now, Duration fresh, Supplier<Map<LocalDate, Double>> fetch) {
        Map<String, Object> f = jdbc.sql("SELECT last_ok, last_fail FROM btc_daily_fetch WHERE source = :s")
                .param("s", source).query().listOfRows().stream().findFirst().orElse(Map.of());
        Instant ok = parse(f.get("last_ok")), fail = parse(f.get("last_fail"));
        if (ok != null && fresh.compareTo(Duration.ZERO) > 0 && ok.isAfter(now.minus(fresh))) return;
        if (fail != null && fail.isAfter(now.minus(BACKOFF)) && (ok == null || fail.isAfter(ok))) return;
        try {
            Map<LocalDate, Double> data = fetch.get();
            save(source, data);
            jdbc.sql("""
                    INSERT INTO btc_daily_fetch(source, last_ok) VALUES (:s, :t)
                    ON CONFLICT(source) DO UPDATE SET last_ok = :t, error = NULL""")
                    .param("s", source).param("t", now.toString()).update();
        } catch (RuntimeException e) {
            log.warn("BTC 每日短线数据 {} 获取失败：{}", source, e.getMessage());
            jdbc.sql("""
                    INSERT INTO btc_daily_fetch(source, last_fail, error) VALUES (:s, :t, :e)
                    ON CONFLICT(source) DO UPDATE SET last_fail = :t, error = :e""")
                    .param("s", source).param("t", now.toString()).param("e", String.valueOf(e.getMessage())).update();
        }
    }

    @Transactional
    public void save(String source, Map<LocalDate, Double> data) {
        for (var e : data.entrySet()) {
            jdbc.sql("INSERT OR REPLACE INTO btc_daily_series(source, day, value) VALUES (:s, :d, :v)")
                    .param("s", source).param("d", e.getKey().toString()).param("v", e.getValue()).update();
        }
    }

    /** 资金费率按 UTC 日取平均（每天 3 次结算）。 */
    static Map<LocalDate, Double> dailyFunding(Map<Long, Double> prints) {
        Map<LocalDate, double[]> acc = new TreeMap<>();
        prints.forEach((t, v) -> {
            double[] a = acc.computeIfAbsent(ContextSources.day(t), k -> new double[2]);
            a[0] += v;
            a[1]++;
        });
        Map<LocalDate, Double> out = new TreeMap<>();
        acc.forEach((d, a) -> out.put(d, a[0] / a[1]));
        return out;
    }

    /** 组装计划日可用的数据。 */
    public MarketContext context(LocalDate day) {
        BtcDailyProperties.Filters f = props.filters();
        List<String> gaps = new ArrayList<>();
        String event = null;
        if (f.fomcDates().contains(day.toString())) event = "美联储议息";
        else if (value(CPI, day) != null || f.cpiDates().contains(day.toString())) event = "CPI 发布";

        Double funding = value(FUNDING, day.minusDays(1));
        if (funding == null) gaps.add(MarketContext.GAP_FUNDING);
        Double fng = value(FNG, day.minusDays(1));
        if (fng == null) gaps.add(MarketContext.GAP_FNG);
        Double oi = value(OI, day.minusDays(1));

        List<Map.Entry<LocalDate, Double>> etf = recent(ETF, day.minusDays(2), 3, 7);
        LocalDate etfDay = null;
        Double etfFlow = null, etf3d = null;
        if (etf.isEmpty()) {
            gaps.add(MarketContext.GAP_ETF);
        } else {
            etfDay = etf.getFirst().getKey();
            etfFlow = etf.getFirst().getValue();
            etf3d = etf.stream().mapToDouble(Map.Entry::getValue).sum();
        }

        Double us10y = null, dollar = null;
        if (f.fredConfigured()) {
            List<Map.Entry<LocalDate, Double>> y = recent(US10Y, day.minusDays(2), 6, 10);
            if (y.size() == 6) us10y = y.getFirst().getValue() - y.getLast().getValue();
            else gaps.add(MarketContext.GAP_US10Y);
            List<Map.Entry<LocalDate, Double>> d = recent(DOLLAR, day.minusDays(2), 6, 21);
            if (d.size() == 6) dollar = (d.getFirst().getValue() / d.getLast().getValue() - 1) * 100;
            else gaps.add(MarketContext.GAP_DOLLAR);
        } else {
            gaps.add(f.cpiCalendarKnown(day) ? MarketContext.GAP_NO_FRED : MarketContext.GAP_NO_FRED_NO_CPI);
        }
        return new MarketContext(day, event, funding, fng, etfDay, etfFlow, etf3d, us10y, dollar, oi, List.copyOf(gaps));
    }

    private Double value(String source, LocalDate day) {
        return jdbc.sql("SELECT value FROM btc_daily_series WHERE source = :s AND day = :d")
                .param("s", source).param("d", day.toString()).query(Double.class).optional().orElse(null);
    }

    /** 不晚于 until 的最近 n 条（新到旧）；最新一条早于 until − maxAgeDays 视为过期，返回空。 */
    private List<Map.Entry<LocalDate, Double>> recent(String source, LocalDate until, int n, int maxAgeDays) {
        List<Map.Entry<LocalDate, Double>> rows = jdbc.sql("""
                SELECT day, value FROM btc_daily_series WHERE source = :s AND day <= :u ORDER BY day DESC LIMIT :n""")
                .param("s", source).param("u", until.toString()).param("n", n)
                .query((rs, i) -> Map.entry(LocalDate.parse(rs.getString(1)), rs.getDouble(2))).list();
        if (rows.isEmpty() || rows.getFirst().getKey().isBefore(until.minusDays(maxAgeDays))) return List.of();
        return rows;
    }

    private static Instant parse(Object o) {
        return o == null ? null : Instant.parse(o.toString());
    }
}
