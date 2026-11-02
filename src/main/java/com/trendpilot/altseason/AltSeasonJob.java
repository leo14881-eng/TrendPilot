package com.trendpilot.altseason;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.job.AppState;
import com.trendpilot.market.Kline;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.notify.Notifier;
import com.trendpilot.research.ResearchService;
import com.trendpilot.universe.UniverseFilter;

/**
 * 山寨季提醒（用户 2026-11-02 "一定要抓住本轮山寨牛市"；只提醒，不交易，不改任何交易规则）。
 * 每天 UTC 00:07（日线收盘后）用 Binance 标的池成交额前 150 的已收盘日线算指标，写 alt_season_daily；
 * 状态从 OFF 进入 EARLY、或从 EARLY 回到 OFF 时发一条【山寨季】Telegram，状态不变不发，每天最多一条。
 * 缺数据不判断（状态不变，记 DATA_MISSING）。规则见 {@link AltSeasonRules}。
 */
@Component
public class AltSeasonJob {

    private static final Logger log = LoggerFactory.getLogger(AltSeasonJob.class);
    static final String STATE = "altseason:state";
    static final String STREAK = "altseason:outperform_days";
    static final int POOL = 150;
    static final int DAYS = 201;

    private final Markets markets;
    private final UniverseFilter filter;
    private final ResearchService research;
    private final AppState state;
    private final JdbcClient jdbc;
    private final Notifier notifier;
    private final boolean enabled;

    public AltSeasonJob(Markets markets, UniverseFilter filter, ResearchService research, AppState state, JdbcClient jdbc,
                        Notifier notifier, @Value("${trendpilot.altseason.enabled:true}") boolean enabled) {
        this.markets = markets;
        this.filter = filter;
        this.research = research;
        this.state = state;
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 7 0 * * *", zone = "UTC")
    public void scheduled() {
        if (!enabled) return;
        try {
            run(Instant.now());
        } catch (RuntimeException e) {
            log.warn("山寨季指标本轮没算出来（只影响提醒）：{}", e.toString());
        }
    }

    /** 算一天的指标并更新状态。@return 写入的状态 */
    public String run(Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1); // 刚收盘的那天
        VenueMarket m = markets.get(Venue.BINANCE);
        if (m == null) return "DATA_MISSING";
        Set<String> blocked = new java.util.HashSet<>(research.excludedSymbols(Venue.BINANCE));
        blocked.addAll(research.tokenizedSymbols(Venue.BINANCE));
        List<Ticker24h> pool = filter.select(m.directory().all(), m.tickers().tickers24h(), Set.of(), blocked).stream()
                .filter(t -> !t.symbol().equals(UniverseFilter.BTC))
                .sorted(Comparator.comparingDouble(Ticker24h::quoteVolume).reversed()).limit(POOL).toList();
        long cutoff = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Map<String, double[]> daily = new LinkedHashMap<>();
        for (Ticker24h t : pool) {
            double[] c = closes(m, t.symbol(), cutoff);
            if (c != null) daily.put(t.symbol(), c);
        }
        double[] btc = closes(m, UniverseFilter.BTC, cutoff);
        double[] eth = closes(m, "ETHUSDT", cutoff);
        AltSeasonRules.Metrics x = AltSeasonRules.compute(daily, btc, eth);
        return record(day, x, now);
    }

    /** 记一天的指标、推进状态，状态变化时发提醒（拆出来便于测试）。 */
    String record(LocalDate day, AltSeasonRules.Metrics x, Instant now) {
        AltSeasonRules.State prev = AltSeasonRules.State.valueOf(state.get(STATE).orElse("OFF"));
        String regime = state.get("regime").orElse(null);
        boolean regimeOk = "CONSTRUCTIVE".equals(regime) || "STRONG".equals(regime);
        int streak = Integer.parseInt(state.get(STREAK).orElse("0"));
        String label;
        AltSeasonRules.State next = prev;
        if (!x.complete()) {
            label = "DATA_MISSING";
        } else {
            streak = x.altVsBtc30() > 0 ? streak + 1 : 0;
            next = AltSeasonRules.next(prev, x, streak, regimeOk);
            label = next.name();
            state.put(STREAK, String.valueOf(streak));
            state.put(STATE, next.name());
        }
        jdbc.sql("""
                INSERT OR REPLACE INTO alt_season_daily(day, regime, pool, breadth50_pct, breadth200_pct, alt30_median_pct, alt_vs_btc30_pct,
                    outperform_days, ethbtc_vs50_pct, ethbtc_vs100_pct, v3_open, state, created_at)
                VALUES (:d, :r, :p, :b50, :b200, :a30, :ab, :od, :e50, :e100, :v3, :s, :t)
                """).param("d", day.toString()).param("r", regime).param("p", x.pool()).param("b50", nan(x.breadth50()))
                .param("b200", nan(x.breadth200())).param("a30", nan(x.alt30())).param("ab", nan(x.altVsBtc30())).param("od", streak)
                .param("e50", nan(x.ethBtcVs50())).param("e100", nan(x.ethBtcVs100())).param("v3", x.v3Open() ? 1 : 0)
                .param("s", label).param("t", now.toString()).update();
        if (next != prev) {
            String head = next == AltSeasonRules.State.EARLY ? "【山寨季】进入山寨季早期（只是提醒，不交易）" : "【山寨季】山寨季信号结束（只是提醒，不交易）";
            notifier.send("altseason:" + day, head + "\n" + line(regime, x, streak)
                    + "\n回测里这个信号多数在山寨行情已经走了一段之后才出现，触发后 30 天山寨池中位数收益并不稳定为正，详见 docs/策略对比记录/2026-11-02-山寨季提醒.md");
        }
        return label;
    }

    static String line(String regime, AltSeasonRules.Metrics x, int streak) {
        return String.format(Locale.ROOT, "行情 %s；50 日线上方 %.0f%%，200 日线上方 %.0f%%；山寨 30 日中位 %+.1f%%，比 BTC %+.1f 点（连续跑赢 %d 天）；"
                        + "ETH/BTC 比 50 日线 %+.1f%%、比 100 日线 %+.1f%%；V3 大盘过滤%s（%d 个币）",
                regime == null ? "?" : regime, x.breadth50(), x.breadth200(), x.alt30(), x.altVsBtc30(), streak, x.ethBtcVs50(),
                x.ethBtcVs100(), x.v3Open() ? "打开" : "关闭", x.pool());
    }

    /** 日报用的一行：最近一天的指标和状态；还没有记录时为空。 */
    public Optional<String> latestLine() {
        return jdbc.sql("SELECT day, regime, pool, breadth50_pct, breadth200_pct, alt30_median_pct, alt_vs_btc30_pct, outperform_days, "
                        + "ethbtc_vs50_pct, ethbtc_vs100_pct, v3_open, state FROM alt_season_daily ORDER BY day DESC LIMIT 1")
                .query((rs, i) -> rs.getString(1) + " " + labelOf(rs.getString(12)) + "；" + line(rs.getString(2),
                        new AltSeasonRules.Metrics(rs.getInt(3), d(rs, 4), d(rs, 5), d(rs, 6), d(rs, 7), d(rs, 9), d(rs, 10), rs.getInt(11) == 1),
                        rs.getInt(8))).optional();
    }

    static String labelOf(String s) {
        return switch (s) {
            case "EARLY" -> "山寨季早期";
            case "OFF" -> "非山寨季";
            default -> "数据不全（不判断）";
        };
    }

    private static double d(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? Double.NaN : v;
    }

    private double[] closes(VenueMarket m, String symbol, long cutoff) {
        try {
            List<Kline> k = m.source().klines(symbol, "1d", null, DAYS + 1).stream().filter(x -> x.closeTime() < cutoff).toList();
            if (k.size() < 51) return null;
            List<Double> c = new ArrayList<>();
            for (Kline x : k) {
                if (!(x.close() > 0)) return null; // 坏数据：这个币不算
                c.add(x.close());
            }
            return c.stream().mapToDouble(Double::doubleValue).toArray();
        } catch (RuntimeException e) {
            log.debug("山寨季 {} 日线获取失败：{}", symbol, e.getMessage());
            return null;
        }
    }

    private static Double nan(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }
}
