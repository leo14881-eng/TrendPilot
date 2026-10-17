package com.trendpilot.v3;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.job.JobRuns;
import com.trendpilot.market.ExecutionSimulator;
import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.Kline;
import com.trendpilot.market.Markets;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.notify.Messages;
import com.trendpilot.notify.Notifier;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.TrendBook;
import com.trendpilot.universe.UniverseFilter;

/**
 * V3 趋势跟踪影子（规则见 {@link TrendRules}）。只在 Binance、只做现货多头的模拟记录，不下真实订单、不影响 V2。
 * <ul>
 * <li>每天 00:03 UTC（日线收盘后）：取标的池和 BTC 的日线（每个币 1 次请求），持仓按"收盘跌破前 10 天最低价"卖出；
 *     大盘多头时按信号买入。BTC 日线或超过一成标的的日线取不到时，本轮不买（数据缺失只能导致不动作）。</li>
 * <li>每小时 :02：持仓按最近一根已收盘 1h K 线判断止损 / 跟踪止损，并更新最高价。</li>
 * <li>买卖都按真实全数量盘口模拟成交；盘口吃不下就不成交（本轮不动作，记日志）。买卖各发一条 Telegram。</li>
 * </ul>
 */
@Component
public class TrendJob {

    private static final Logger log = LoggerFactory.getLogger(TrendJob.class);
    static final String JOB_DAILY = "V3_DAILY";
    static final String JOB_HOURLY = "V3_HOURLY";
    static final double DEPTH_SPAN_PCT = 2.5;

    private final Markets markets;
    private final TrendBook book;
    private final JdbcClient jdbc;
    private final Notifier notifier;
    private final JobRuns runs;
    private final UniverseFilter filter;
    private final double feeRate;
    private final boolean enabled;

    public TrendJob(Markets markets, TrendBook book, JdbcClient jdbc, Notifier notifier, JobRuns runs, StrategyParams params,
                    @Value("${trendpilot.v3.enabled:true}") boolean enabled) {
        this.markets = markets;
        this.book = book;
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.runs = runs;
        this.filter = new UniverseFilter(params);
        this.feeRate = params.feeRate();
        this.enabled = enabled;
    }

    /** 一笔持仓的止损状态。 */
    record State(double entry, double atr, double initStop, double peak, Long lastHour) {
        double stopLine() {
            return TrendRules.stopLine(initStop, peak, atr);
        }
    }

    @Scheduled(cron = "0 3 0 * * *", zone = "UTC")
    public void scheduledDaily() {
        if (enabled) runDaily(Instant.now());
    }

    @Scheduled(cron = "0 2 * * * *", zone = "UTC")
    public void scheduledHourly() {
        if (enabled) runHourly(Instant.now());
    }

    /** 日线：10 日低点离场 + 新买入。@return 本轮买入和卖出的说明 */
    public synchronized List<String> runDaily(Instant now) {
        String gen = "V3D-" + now.truncatedTo(ChronoUnit.MINUTES);
        Instant started = Instant.now();
        List<String> done = new ArrayList<>();
        String status = "COMPLETE";
        try {
            VenueMarket m = markets.get(Venue.BINANCE);
            if (m == null) return done;
            List<Ticker24h> pool = filter.select(m.directory().all(), m.tickers().tickers24h()).stream()
                    .filter(t -> !t.symbol().equals(UniverseFilter.BTC))
                    .sorted(Comparator.comparingDouble(Ticker24h::quoteVolume).reversed())
                    .limit(TrendRules.POOL).toList();
            Set<String> need = new HashSet<>();
            pool.forEach(t -> need.add(t.symbol()));
            book.positions().forEach(p -> need.add(p.symbol()));
            need.add(UniverseFilter.BTC);
            Map<String, List<Kline>> daily = new LinkedHashMap<>();
            int missing = 0;
            for (String s : need) {
                try {
                    long t = now.toEpochMilli();
                    List<Kline> k = m.source().klines(s, "1d", null, TrendRules.MIN_DAYS + 1).stream()
                            .filter(x -> x.closeTime() < t).toList();
                    if (k.size() >= TrendRules.MIN_DAYS) daily.put(s, k);
                    else missing++;
                } catch (RuntimeException e) {
                    missing++;
                    log.debug("V3 {} 日线获取失败：{}", s, e.toString());
                }
            }
            // 1. 持仓：收盘跌破前 10 天最低价 → 卖
            for (Position p : book.positions()) {
                List<Kline> k = daily.get(p.symbol());
                if (k == null) continue;
                Arrays a = Arrays.of(k);
                int end = a.c.length - 1;
                if (k.getLast().closeTime() < p.openedAt().toEpochMilli()) continue; // 买入之后还没有收盘的日线
                if (TrendRules.belowExitLow(a.l, a.c, end)) {
                    String r = sell(m, p, "LOW10_EXIT", gen, now);
                    if (r != null) done.add(r);
                }
            }
            // 2. 大盘过滤
            List<Kline> btc = daily.get(UniverseFilter.BTC);
            boolean dataOk = btc != null && missing <= need.size() / 10;
            if (!dataOk) status = "PARTIAL_DATA:NO_ENTRIES missing=" + missing;
            boolean btcUp = false;
            int above = 0, counted = 0;
            if (btc != null) {
                Arrays b = Arrays.of(btc);
                int end = b.c.length - 1;
                btcUp = b.c[end] > TrendRules.sma(b.c, end, 50);
            }
            for (Ticker24h t : pool) {
                List<Kline> k = daily.get(t.symbol());
                if (k == null) continue;
                Arrays a = Arrays.of(k);
                int end = a.c.length - 1;
                counted++;
                if (a.c[end] > TrendRules.sma(a.c, end, 20)) above++;
            }
            boolean regime = btcUp && counted > 0 && above >= TrendRules.BREADTH_MIN * counted;
            log.info("V3 日线：标的 {} 个（缺 {}），BTC 在 50 日线上方 {}，池子 {}/{} 在 20 日线上方，大盘过滤 {}",
                    pool.size(), missing, btcUp, above, counted, regime ? "打开" : "关闭");
            // 3. 买入
            if (regime && dataOk) {
                Set<String> held = new HashSet<>();
                book.positions().forEach(p -> held.add(p.symbol()));
                for (Ticker24h t : pool) {
                    List<Kline> k = daily.get(t.symbol());
                    if (k == null || held.contains(t.symbol())) continue;
                    List<Position> open = book.positions();
                    if (open.size() >= TrendRules.MAX_POSITIONS) break;
                    Arrays a = Arrays.of(k);
                    int end = a.c.length - 1;
                    if (!TrendRules.breakout(a.h, a.c, a.v, end)) continue;
                    double used = open.stream().mapToDouble(p -> p.costBasis().doubleValue()).sum();
                    String r = buy(m, t, a, used, gen, now);
                    if (r != null) {
                        done.add(r);
                        held.add(t.symbol());
                    }
                }
            }
            return done;
        } catch (RuntimeException e) {
            status = "FAILED";
            log.error("V3 日线任务出错", e);
            return done;
        } finally {
            runs.record(JOB_DAILY, gen, started, Instant.now(), status.equals("FAILED") ? "FAILED" : "SUCCESS", status,
                    String.join("; ", done));
        }
    }

    /** 每小时：按最近一根已收盘 1h K 线判断止损 / 跟踪止损，再更新最高价。 */
    public synchronized List<String> runHourly(Instant now) {
        String gen = "V3H-" + now.truncatedTo(ChronoUnit.MINUTES);
        Instant started = Instant.now();
        List<String> done = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            VenueMarket m = markets.get(Venue.BINANCE);
            if (m == null) return done;
            for (Position p : book.positions()) {
                State st = state(p);
                if (st == null) continue;
                Kline h;
                try {
                    long t = now.toEpochMilli();
                    List<Kline> k = m.source().klines(p.symbol(), "1h", null, 3).stream().filter(x -> x.closeTime() < t).toList();
                    h = k.isEmpty() ? null : k.getLast();
                } catch (RuntimeException e) {
                    h = null;
                }
                if (h == null) {
                    skipped.add(p.symbol());
                    continue;
                }
                if (st.lastHour() != null && h.openTime() <= st.lastHour()) continue;
                if (h.openTime() < p.openedAt().toEpochMilli()) continue;
                double line = st.stopLine();
                if (h.close() <= line) {
                    String why = line > st.initStop() + 1e-12 ? "TRAIL_STOP" : "STOP_LOSS";
                    String r = sell(m, p, why, gen, now);
                    if (r != null) {
                        done.add(r);
                        continue;
                    }
                }
                // 浮动盈亏按这根 1h 收盘价扣卖出手续费估算（报告和回撤用；卖出时仍按全数量盘口成交）
                book.update(p, BigDecimal.valueOf(p.qty().doubleValue() * h.close() * (1 - feeRate) - p.costBasis().doubleValue()), now);
                jdbc.sql("UPDATE v3_state SET peak_high = :p, last_hour = :h WHERE symbol = :s AND opened_at = :o")
                        .param("p", Math.max(st.peak(), h.high())).param("h", h.openTime())
                        .param("s", p.symbol()).param("o", p.openedAt().toString()).update();
            }
            if (!skipped.isEmpty()) log.warn("V3 本轮缺 1h K 线、无法判断止损：{}", skipped);
            book.recordEquity(now);
            return done;
        } finally {
            runs.record(JOB_HOURLY, gen, started, Instant.now(), "SUCCESS",
                    skipped.isEmpty() ? "COMPLETE" : "PARTIAL_DATA:SKIPPED=" + skipped, String.join("; ", done));
        }
    }

    State state(Position p) {
        return jdbc.sql("SELECT entry_price, atr, init_stop, peak_high, last_hour FROM v3_state WHERE symbol = :s AND opened_at = :o")
                .param("s", p.symbol()).param("o", p.openedAt().toString())
                .query((rs, i) -> new State(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getObject(5) == null ? null : rs.getLong(5)))
                .optional().orElse(null);
    }

    private String buy(VenueMarket m, Ticker24h t, Arrays a, double used, String gen, Instant now) {
        int end = a.c.length - 1;
        double atr = TrendRules.atr(a.h, a.l, a.c, end);
        double price = a.c[end];
        if (!(atr > 0) || !(price > 0)) return null;
        double dist = TrendRules.stopDistance(atr, price);
        double notional = Math.min(TrendRules.notional(dist), TrendRules.CAPITAL - used);
        if (notional < 100) return null;
        OrderBook ob;
        try {
            ob = m.depth().getCovering(t.symbol(), DEPTH_SPAN_PCT, notional * 1.5);
        } catch (RuntimeException e) {
            log.warn("V3 {} 盘口拿不到，本轮不买：{}", t.symbol(), e.toString());
            return null;
        }
        if (ob == null || !ob.sane()) return null;
        Fill f = ExecutionSimulator.buy(ob, BigDecimal.valueOf(notional), feeRate);
        if (!f.complete()) {
            log.info("V3 {} 盘口吃不下 {} U，不买", t.symbol(), Math.round(notional));
            return null;
        }
        Position p = book.open(Venue.BINANCE, t.symbol(), f, gen, now);
        double fill = f.avgPrice().doubleValue();
        double initStop = fill * (1 - dist);
        jdbc.sql("""
                INSERT OR REPLACE INTO v3_state(symbol, opened_at, entry_price, atr, init_stop, peak_high)
                VALUES (:s, :o, :e, :a, :st, :pk)
                """).param("s", t.symbol()).param("o", p.openedAt().toString()).param("e", fill).param("a", atr)
                .param("st", initStop).param("pk", fill).update();
        String line = String.format(Locale.ROOT, "🟣 V3 影子买入 %s，%s U，均价 %s，止损 %s（−%.1f%%）",
                t.symbol(), Messages.money(f.totalCost()), Messages.price(f.avgPrice()), Messages.price(BigDecimal.valueOf(initStop)),
                dist * 100);
        notifier.send("v3-buy:" + t.symbol() + ":" + p.openedAt(), line);
        log.info(line);
        return line;
    }

    private String sell(VenueMarket m, Position p, String reason, String gen, Instant now) {
        OrderBook ob;
        try {
            ob = m.depth().getCovering(p.symbol(), DEPTH_SPAN_PCT, p.costBasis().doubleValue() * 1.5);
        } catch (RuntimeException e) {
            log.warn("V3 {} 盘口拿不到，本轮无法卖出（{}）：{}", p.symbol(), reason, e.toString());
            return null;
        }
        if (ob == null || !ob.sane()) return null;
        Fill f = ExecutionSimulator.sell(ob, p.qty(), feeRate);
        if (!f.complete()) {
            log.warn("V3 {} 盘口吃不下全部数量，本轮不卖（{}）", p.symbol(), reason);
            return null;
        }
        BigDecimal pnl = f.netProceeds().subtract(p.costBasis());
        book.close(p, f, pnl, reason, gen, now);
        String label = switch (reason) {
            case "STOP_LOSS" -> "止损";
            case "TRAIL_STOP" -> "跟踪止损";
            case "LOW10_EXIT" -> "跌破 10 日低点";
            default -> reason;
        };
        String line = String.format(Locale.ROOT, "%s V3 影子卖出 %s（%s），净盈亏 %s U（%.2f%%）", pnl.signum() >= 0 ? "🔵" : "🔴",
                p.symbol(), label, Messages.money(pnl), pnl.doubleValue() / p.costBasis().doubleValue() * 100);
        notifier.send("v3-sell:" + p.symbol() + ":" + p.openedAt(), line);
        log.info(line);
        return line;
    }

    /** 日线数组：最高、最低、收盘、成交额（升序，只含已收盘）。 */
    record Arrays(double[] h, double[] l, double[] c, double[] v) {
        static Arrays of(List<Kline> k) {
            int n = k.size();
            double[] h = new double[n], l = new double[n], c = new double[n], v = new double[n];
            for (int i = 0; i < n; i++) {
                h[i] = k.get(i).high();
                l[i] = k.get(i).low();
                c[i] = k.get(i).close();
                v[i] = k.get(i).quoteVolume();
            }
            return new Arrays(h, l, c, v);
        }
    }
}
