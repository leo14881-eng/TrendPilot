package com.trendpilot.btcdaily;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendpilot.market.BarsLoader;
import com.trendpilot.market.Kline;
import com.trendpilot.market.KlineStore;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.notify.Notifier;

/**
 * BTC 每日短线（影子）：每天生成计划，每 15 分钟用已收盘的 15m K 线判断买入/加仓/止盈。
 * 15m K 线读现有缓存（Discovery 每轮都加载 BTC，WebSocket 也在写），每天只多请求 1 次日线。
 */
@Service
public class BtcDailyService {

    private static final Logger log = LoggerFactory.getLogger(BtcDailyService.class);
    static final int DAILY_LIMIT = 120;

    private final BtcDailyProperties props;
    private final BtcDailyStore store;
    private final Markets markets;
    private final KlineStore klines;
    private final Notifier notifier;
    private final ObjectMapper json;
    private final ContextService context;
    /** 本版本回测确认后实盘用的卖出方式；null = 还没确认。 */
    private volatile String liveExit;
    /** strategy = AUTO 时本版本回测选定的策略；null = 还没确认。 */
    private volatile String liveStrategy;

    public BtcDailyService(BtcDailyProperties props, BtcDailyStore store, Markets markets, KlineStore klines,
                           Notifier notifier, ObjectMapper json, ContextService context) {
        this.context = context;
        this.props = props;
        this.store = store;
        this.markets = markets;
        this.klines = klines;
        this.notifier = notifier;
        this.json = json;
    }

    /** 一次检查：补处理新收盘的 15m K 线，缺当天计划时生成并推送。 */
    public synchronized void tick(Instant now) {
        VenueMarket m = markets.get(Venue.BINANCE);
        if (m == null) return;
        String sym = props.symbol();
        BtcDailyProperties p = live();
        BtcDailyStore.State st = store.state();
        Book book = st.book();
        long lastClosed = BarsLoader.lastClosedOpenTime(now);
        Long last = st.lastBarOpen();
        List<Kline> daily = null;
        List<Fill> fills = new ArrayList<>();

        if (last == null) {
            last = lastClosed; // 首次启动：从现在开始，不补算过去的 K 线
        } else if (last < lastClosed) {
            m.bars().load(Set.of(sym), now); // 只补缓存里没有的部分
            List<Kline> bars = klines.range(Venue.BINANCE, sym, last + BarsLoader.QUARTER_MS, lastClosed);
            long expected = (lastClosed - last) / BarsLoader.QUARTER_MS;
            if (bars.size() < expected) {
                // 停机较久：缓存只补最近 104 根，更早的缺口按时间段补齐，不跳过期间的成交和止盈
                backfill(m, sym, last + BarsLoader.QUARTER_MS, lastClosed);
                bars = klines.range(Venue.BINANCE, sym, last + BarsLoader.QUARTER_MS, lastClosed);
            }
            if (!bars.isEmpty() && bars.getFirst().openTime() != last + BarsLoader.QUARTER_MS) {
                log.warn("BTC 每日短线：K 线有缺口，从 {} 接着处理", Instant.ofEpochMilli(bars.getFirst().openTime()));
            }
            for (Kline bar : bars) {
                LocalDate day = BtcDailyRules.dayOf(bar.openTime());
                DailyPlan plan = store.plan(day).orElse(null);
                if (plan == null) {
                    if (daily == null) daily = daily(m);
                    plan = createPlan(day, daily, book, now);
                }
                if (plan == null) break; // 日线暂缺：下次再处理，不跳过 K 线
                List<Fill> f = BtcDailyRules.onBar(plan, book, bar, p);
                last = bar.openTime();
                if (!f.isEmpty()) {
                    fills.addAll(f);
                    store.save(book, last, f, props.version(), plan.reasons(), book.lastRound, now);
                    Book.Round round = book.lastRound;
                    for (Fill x : f) notifier.send(fillKey(x), BtcDailyMessages.fill(x, book, plan, round, p));
                }
            }
        }
        store.save(book, last, List.of(), props.version(), now);

        LocalDate today = BtcDailyRules.dayOf(now.toEpochMilli());
        if (store.plan(today).isEmpty()) {
            if (daily == null) daily = daily(m);
            createPlan(today, daily, book, now);
        }
        if (!fills.isEmpty()) log.info("BTC 每日短线成交 {} 笔", fills.size());
    }

    static final int BACKFILL_LIMIT = 1000;      // 每次请求约 10 天的 15m K 线
    static final int BACKFILL_MAX_REQUESTS = 4;

    private void backfill(VenueMarket m, String sym, long from, long lastClosed) {
        long cursor = from;
        for (int i = 0; i < BACKFILL_MAX_REQUESTS && cursor <= lastClosed; i++) {
            List<Kline> closed;
            try {
                closed = m.source().klines(sym, "15m", cursor, BACKFILL_LIMIT).stream()
                        .filter(k -> k.openTime() >= from && k.openTime() <= lastClosed).toList();
            } catch (RuntimeException e) {
                log.warn("BTC 每日短线：补 K 线失败，下次再试：{}", e.getMessage());
                return;
            }
            if (closed.isEmpty()) return;
            klines.upsert(Venue.BINANCE, sym, closed);
            cursor = closed.getLast().openTime() + BarsLoader.QUARTER_MS;
        }
        log.info("BTC 每日短线：已按时间段补齐 K 线到 {}", Instant.ofEpochMilli(cursor));
    }

    private DailyPlan createPlan(LocalDate day, List<Kline> daily, Book book, Instant now) {
        long start = BtcDailyRules.dayStart(day);
        List<Kline> before = daily.stream().filter(k -> k.openTime() < start).toList();
        Kline today = daily.stream().filter(k -> k.openTime() == start).findFirst().orElse(null);
        if (today == null) {
            log.warn("BTC 每日短线：没有 {} 的日线，暂不生成计划", day);
            return null;
        }
        try {
            context.refreshDue(now);
        } catch (RuntimeException e) {
            log.warn("BTC 每日短线：市场 / 宏观数据刷新失败：{}", e.getMessage());
        }
        MarketContext ctx = context.context(day);
        BtcDailyProperties p = live();
        DailyPlan plan = null;
        if (p.isV3()) {
            List<Kline> hourly = hourlyBefore(start);
            if (hourly.size() >= BtcDailyRules.HOURLY_NEEDED) {
                plan = BtcDailyRules.planV3(day, before, hourly, today.open(), book, p, ctx);
            } else {
                log.warn("BTC 每日短线：1h K 线不足（{} 根），{} 按 V2 生成计划", hourly.size(), day);
            }
        }
        if (plan == null) plan = BtcDailyRules.plan(day, before, today.open(), book, p, ctx);
        store.savePlan(plan, now);
        LocalDate yesterday = day.minusDays(1);
        notifier.send("btc-daily-plan-" + day, BtcDailyMessages.plan(plan, book, store.daySummary(yesterday), live()));
        remindCapReview(day);
        return plan;
    }

    /** 到了资金上限复评日发一次提醒（只提醒，不自动放大上限）。 */
    private void remindCapReview(LocalDate day) {
        LocalDate until;
        try {
            until = LocalDate.parse(props.capUntil());
        } catch (RuntimeException e) {
            return;
        }
        if (day.isBefore(until)) return;
        BtcDailyStore.State st = store.state();
        notifier.send("btc-daily-cap-review-" + until, "【BTC 每日短线·影子】资金上限 " + Math.round(props.capUsdt())
                + " USDT 已到复评日 " + until + "\n累计完成 " + st.book().rounds() + " 轮，已实现 "
                + String.format(java.util.Locale.ROOT, "%.2f", st.book().realized()) + " USDT，最大浮亏 "
                + String.format(java.util.Locale.ROOT, "%.2f", st.book().maxFloatLoss())
                + " USDT\n上限不会自动放大，是否调整由你决定。");
    }

    /**
     * 实盘生效的参数。strategy = AUTO 时：本版本回测确认 V3 不比 V2 差才用 V3，确认前和不达标都用 V2；
     * V2 的卖出方式需要回测确认时同理，确认前按 FIXED。
     */
    BtcDailyProperties live() {
        BtcDailyProperties p = props;
        if ("AUTO".equals(props.strategy())) {
            String s = liveStrategy;
            if (s == null) {
                s = store.backtest(props.version()).map(j -> jsonField(j, "liveStrategy")).orElse(null);
                liveStrategy = s;
            }
            p = props.withStrategy(s == null ? "V2" : s);
        }
        BtcDailyProperties.Exit x = p.exit();
        if ("FIXED".equals(x.mode()) || (!x.requireBacktest() && !"AUTO".equals(x.mode()))) return p;
        String mode = liveExit;
        if (mode == null) {
            mode = store.backtest(props.version()).map(j -> jsonField(j, "liveExit")).orElse(null);
            liveExit = mode;
        }
        return p.withExitMode(mode == null ? "FIXED" : mode);
    }

    /** 测试用：清掉已缓存的回测结论。 */
    void forgetLiveExit() {
        liveExit = null;
        liveStrategy = null;
    }

    private String jsonField(String backtestJson, String field) {
        try {
            Object v = json.readValue(backtestJson, Map.class).get(field);
            return v instanceof String sv ? sv : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 新策略至少要完成的轮数：几乎不交易时"回撤小"不能算更好。 */
    static final int MIN_ROUNDS = 10;

    /**
     * 不比基准差：至少完成 {@link #MIN_ROUNDS} 轮，总盈亏不低于基准，最大回撤不超过基准 1.1 倍，
     * 最长被套不多于基准 2 天。
     */
    static boolean notWorse(BtcDailyBacktest.Result r, BtcDailyBacktest.Result base) {
        return r.rounds() >= MIN_ROUNDS
                && r.totalPnl() >= base.totalPnl() - 0.01
                && r.maxDrawdown() <= base.maxDrawdown() * 1.1 + 0.01
                && r.maxHoldDays() <= base.maxHoldDays() + 2;
    }

    /**
     * 候选卖出方式（AUTO 时为 candidates，否则为 mode 本身）和现行 FIXED 比：总盈亏不低于 FIXED，
     * 最大回撤不超过 FIXED 的 1.1 倍，最长被套不多于 FIXED 2 天，才算"不比现行差"；
     * 达标的里选总盈亏最高的实盘生效，都不达标仍用 FIXED，等用户决定。
     */
    static String decideExit(BtcDailyProperties.Exit x, Map<String, BtcDailyBacktest.Result> exits) {
        if ("FIXED".equals(x.mode())) return "FIXED";
        List<String> cands = "AUTO".equals(x.mode()) ? x.candidates() : List.of(x.mode());
        if (!x.requireBacktest() && !"AUTO".equals(x.mode())) return x.mode();
        BtcDailyBacktest.Result f = exits.get("FIXED");
        if (f == null) return "FIXED";
        String best = "FIXED";
        double bestPnl = Double.NEGATIVE_INFINITY;
        for (String c : cands) {
            BtcDailyBacktest.Result r = exits.get(c);
            if (r == null) continue;
            if (notWorse(r, f) && r.totalPnl() > bestPnl) {
                best = c;
                bestPnl = r.totalPnl();
            }
        }
        return best;
    }

    /** 计划日之前最近 72 根已收盘 1h K 线（每天生成计划时 1 次请求）。 */
    private List<Kline> hourlyBefore(long start) {
        VenueMarket m = markets.get(Venue.BINANCE);
        if (m == null) return List.of();
        try {
            return m.source().klines(props.symbol(), "1h", start - 72 * BarsLoader.HOUR_MS, 72).stream()
                    .filter(k -> k.closeTime() < start).toList();
        } catch (RuntimeException e) {
            log.warn("BTC 每日短线：1h K 线获取失败：{}", e.getMessage());
            return List.of();
        }
    }

    private List<Kline> daily(VenueMarket m) {
        return m.source().klines(props.symbol(), "1d", null, DAILY_LIMIT);
    }

    private static String fillKey(Fill f) {
        return "btc-daily-fill-" + f.barOpenTime() + "-" + f.side() + "-" + f.level();
    }

    /** 上线回测：每个参数版本只跑一次，约 10 次 REST 请求（走现有限速器）。 */
    public synchronized void backtestIfNeeded(Instant now) throws Exception {
        if (store.backtest(props.version()).isPresent()) return;
        VenueMarket m = markets.get(Venue.BINANCE);
        if (m == null) return;
        String sym = props.symbol();
        long t = now.toEpochMilli();
        List<Kline> daily = m.source().klines(sym, "1d", null, Math.min(1000, props.backtestDays() + DAILY_LIMIT))
                .stream().filter(k -> k.closeTime() < t).toList();
        List<Kline> hourly = new ArrayList<>();
        long rangeStart = BtcDailyRules.dayStart(BtcDailyRules.dayOf(t).minusDays(props.backtestDays()));
        long from = rangeStart - 4 * 86_400_000L; // V3 计划要用之前的 1h K 线
        while (from < t) {
            List<Kline> page = m.source().klines(sym, "1h", from, 1000);
            if (page.isEmpty()) break;
            page.stream().filter(k -> k.closeTime() < t).forEach(hourly::add);
            long next = page.getLast().openTime() + BarsLoader.HOUR_MS;
            if (next <= from || page.size() < 1000) break;
            from = next;
        }
        List<Kline> bars = hourly.stream().filter(k -> k.openTime() >= rangeStart).toList();
        LocalDate firstDay = BtcDailyRules.dayOf(rangeStart);
        context.loadHistory(firstDay.minusDays(2), now);
        // 现行 V2（固定止盈）、V3（按行情挂单 + 分批跟踪）、V3 + 三种被套保护，都加市场 / 宏观过滤
        Map<String, BtcDailyBacktest.Result> runs = new java.util.LinkedHashMap<>();
        runs.put("V2", BtcDailyBacktest.run(daily, bars, hourly, props.withStrategy("V2").withExitMode("FIXED"), context::context));
        BtcDailyProperties v3 = props.withStrategy("V3").withGuard("NONE");
        runs.put("V3", BtcDailyBacktest.run(daily, bars, hourly, v3, context::context));
        // 被套保护对比（只回测，实盘不止损，等用户决定）
        for (String g : List.of("TREND", "TIME", "STOP")) {
            runs.put("V3+" + g, BtcDailyBacktest.run(daily, bars, hourly, v3.withGuard(g), context::context));
        }
        String chosen = "AUTO".equals(props.strategy())
                ? (notWorse(runs.get("V3"), runs.get("V2")) ? "V3" : "V2") : props.strategy();
        String exit = decideExit(props.exit(), Map.of("FIXED", runs.get("V2")));
        store.saveBacktest(props.version(), json.writeValueAsString(
                Map.of("runs", runs, "liveStrategy", chosen, "liveExit", exit)), now);
        liveStrategy = chosen;
        liveExit = exit;
        notifier.send("btc-daily-backtest-" + props.version(), BtcDailyMessages.strategies(runs, props, chosen));
        log.info("BTC 每日短线回测完成，实盘策略 {}：{}", chosen, runs);
    }

    /** /api/btc-daily 和每日快照用。 */
    public Map<String, Object> summary(Instant now) {
        BtcDailyStore.State st = store.state();
        Book b = st.book();
        LocalDate today = BtcDailyRules.dayOf(now.toEpochMilli());
        DailyPlan plan = store.plan(today).orElse(null);
        Map<String, Object> s = new LinkedHashMap<>();
        BtcDailyProperties p = live();
        s.put("version", props.version());
        s.put("strategy", p.strategy());
        s.put("exitMode", p.isV3() ? "V3" : p.exit().mode());
        s.put("capUsdt", props.capUsdt());
        s.put("capUntil", props.capUntil());
        s.put("plan", plan);
        s.put("context", context.context(today));
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("holding", b.holding());
        pos.put("qty", b.qty());
        pos.put("cost", BtcDailyRules.round2(b.cost()));
        pos.put("avgCost", BtcDailyRules.round2(b.avgCost()));
        pos.put("buys", b.buys());
        pos.put("openedDay", b.openedDay());
        if (b.holding() && plan != null) {
            pos.put("takeProfitPrice", BtcDailyRules.round2(BtcDailyRules.takeProfitPrice(plan, b, p)));
            if (b.trailPeak > 0) pos.put("trailPeak", BtcDailyRules.round2(b.trailPeak));
        }
        Long lastBar = st.lastBarOpen();
        Double lastPrice = lastBar == null ? null : klines.range(Venue.BINANCE, props.symbol(), lastBar, lastBar)
                .stream().findFirst().map(Kline::close).orElse(null);
        if (b.holding() && lastPrice != null) {
            pos.put("unrealized", BtcDailyRules.round2(b.unrealized(lastPrice, props.feeRate())));
        }
        pos.put("roundMaxFloatLoss", BtcDailyRules.round2(b.roundMaxFloatLoss()));
        pos.put("maePct", BtcDailyRules.round2(b.maePct()));
        pos.put("mfePct", BtcDailyRules.round2(b.mfePct()));
        pos.put("openFilters", b.openFilters);
        pos.put("stopHits", b.stopHits());
        s.put("position", pos);
        s.put("realized", BtcDailyRules.round2(b.realized()));
        s.put("rounds", b.rounds());
        s.put("wins", b.wins());
        s.put("maxFloatLoss", BtcDailyRules.round2(b.maxFloatLoss()));
        s.put("lastBarAt", lastBar == null ? null : Instant.ofEpochMilli(lastBar));
        s.put("stale", lastBar == null || now.toEpochMilli() - lastBar > Duration.ofHours(1).toMillis());
        s.put("roundStats", store.roundStats(b, lastPrice, props.stopCandidatesPct(), props.feeRate()));
        s.put("recentRounds", store.recentRounds(20));
        s.put("recentTrades", store.recentTrades(20));
        s.put("backtest", store.backtest(props.version()).map(this::readJson).orElse(null));
        return s;
    }

    private Object readJson(String s) {
        try {
            return json.readValue(s, Map.class);
        } catch (Exception e) {
            return s;
        }
    }
}
