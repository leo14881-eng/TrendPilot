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
                List<Fill> f = BtcDailyRules.onBar(plan, book, bar, props);
                last = bar.openTime();
                if (!f.isEmpty()) {
                    fills.addAll(f);
                    store.save(book, last, f, props.version(), now);
                    for (Fill x : f) notifier.send(fillKey(x), BtcDailyMessages.fill(x, book, plan, props));
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
        DailyPlan plan = BtcDailyRules.plan(day, before, today.open(), book, props, ctx);
        store.savePlan(plan, now);
        LocalDate yesterday = day.minusDays(1);
        notifier.send("btc-daily-plan-" + day, BtcDailyMessages.plan(plan, book, store.daySummary(yesterday), props));
        return plan;
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
        long from = BtcDailyRules.dayStart(BtcDailyRules.dayOf(t).minusDays(props.backtestDays()));
        while (from < t) {
            List<Kline> page = m.source().klines(sym, "1h", from, 1000);
            if (page.isEmpty()) break;
            page.stream().filter(k -> k.closeTime() < t).forEach(hourly::add);
            long next = page.getLast().openTime() + BarsLoader.HOUR_MS;
            if (next <= from || page.size() < 1000) break;
            from = next;
        }
        LocalDate firstDay = hourly.isEmpty() ? BtcDailyRules.dayOf(t) : BtcDailyRules.dayOf(hourly.getFirst().openTime());
        context.loadHistory(firstDay.minusDays(2), now);
        BtcDailyBacktest.Result base = BtcDailyBacktest.run(daily, hourly, props, null);
        BtcDailyBacktest.Result filtered = BtcDailyBacktest.run(daily, hourly, props, context::context);
        store.saveBacktest(props.version(), json.writeValueAsString(Map.of("base", base, "filtered", filtered)), now);
        notifier.send("btc-daily-backtest-" + props.version(), BtcDailyMessages.backtest(base, filtered, props));
        log.info("BTC 每日短线回测完成：基准 {}，加过滤 {}", base, filtered);
    }

    /** /api/btc-daily 和每日快照用。 */
    public Map<String, Object> summary(Instant now) {
        BtcDailyStore.State st = store.state();
        Book b = st.book();
        LocalDate today = BtcDailyRules.dayOf(now.toEpochMilli());
        DailyPlan plan = store.plan(today).orElse(null);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("version", props.version());
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
            pos.put("takeProfitPrice", BtcDailyRules.round2(BtcDailyRules.takeProfitPrice(plan, b, props)));
        }
        Long lastBar = st.lastBarOpen();
        if (b.holding() && lastBar != null) {
            klines.range(Venue.BINANCE, props.symbol(), lastBar, lastBar).stream().findFirst().ifPresent(k ->
                    pos.put("unrealized", BtcDailyRules.round2(b.unrealized(k.close(), props.feeRate()))));
        }
        pos.put("roundMaxFloatLoss", BtcDailyRules.round2(b.roundMaxFloatLoss()));
        s.put("position", pos);
        s.put("realized", BtcDailyRules.round2(b.realized()));
        s.put("rounds", b.rounds());
        s.put("wins", b.wins());
        s.put("maxFloatLoss", BtcDailyRules.round2(b.maxFloatLoss()));
        s.put("lastBarAt", lastBar == null ? null : Instant.ofEpochMilli(lastBar));
        s.put("stale", lastBar == null || now.toEpochMilli() - lastBar > Duration.ofHours(1).toMillis());
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
