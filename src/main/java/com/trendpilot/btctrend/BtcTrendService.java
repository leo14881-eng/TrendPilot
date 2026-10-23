package com.trendpilot.btctrend;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.trendpilot.btctrend.BtcTrendStore.Slot;
import com.trendpilot.btctrend.BtcTrendStore.State;
import com.trendpilot.btctrend.MaTrendRules.Action;
import com.trendpilot.market.Kline;

/**
 * BTC 均线趋势的一次运行（规则见 {@link MaTrendRules}，账本见 {@link BtcTrendStore}）。与其他策略完全独立。
 * <ul>
 * <li>只用已收盘的 UTC 日线：开盘时间 + 1 天 ≤ 现在的才算收盘，当天进行中的那根不参与均线和信号。</li>
 * <li>信号日 D 必须是昨天（UTC）：最新已收盘日线比昨天旧说明数据缺失，本次不动作并告警。</li>
 * <li>执行价 = D+1（今天 UTC）日线的开盘价；今天那根还取不到时用 D 的收盘价（两者通常相同）。</li>
 * <li>每份记录最近处理过的信号日，同一信号日重复运行不会重复成交。</li>
 * <li>第一次运行 = 启动：4 份都是 USDT，按最新已收盘日线判断，满足买入条件的份直接买入。</li>
 * </ul>
 */
@Component
public class BtcTrendService {

    static final long DAY_MS = 86_400_000L;

    public enum Status { DONE, ALREADY, NO_DATA, STALE }

    public record Outcome(Status status, String signalDay, List<String> actions, String daily) {
    }

    private final BtcTrendStore store;
    private final TrendExecutor executor;

    @org.springframework.beans.factory.annotation.Autowired
    public BtcTrendService(BtcTrendStore store) {
        this(store, TrendExecutor.SHADOW);
    }

    BtcTrendService(BtcTrendStore store, TrendExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    static String day(long openTimeMs) {
        return Instant.ofEpochMilli(openTimeMs).atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    /** 已收盘日线（升序、去重）。 */
    static List<Kline> closed(List<Kline> raw, Instant now) {
        Map<Long, Kline> byOpen = new LinkedHashMap<>();
        raw.stream().sorted(Comparator.comparingLong(Kline::openTime)).forEach(k -> byOpen.put(k.openTime(), k));
        long t = now.toEpochMilli();
        return byOpen.values().stream().filter(k -> k.openTime() + DAY_MS <= t).toList();
    }

    public synchronized Outcome run(List<Kline> raw, Instant now) {
        List<Kline> bars = closed(raw, now);
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        if (bars.isEmpty()) return new Outcome(Status.NO_DATA, null, List.of(), null);
        Kline d = bars.getLast();
        String signalDay = day(d.openTime());
        if (LocalDate.parse(signalDay).isBefore(today.minusDays(1))) {
            return new Outcome(Status.STALE, signalDay, List.of(), null);
        }
        // 当前价：今天进行中那根的收盘（用于估值），没有就用 D 收盘
        Kline todayBar = raw.stream().filter(k -> k.openTime() == d.openTime() + DAY_MS).findFirst().orElse(null);
        store.putMeta("last_price", fmt(todayBar != null ? todayBar.close() : d.close()));

        store.ensureSlots(now);
        List<Slot> slots = store.slots();
        if (slots.stream().allMatch(s -> s.lastSignalDay() != null && s.lastSignalDay().compareTo(signalDay) >= 0)) {
            return new Outcome(Status.ALREADY, signalDay, List.of(), null);
        }
        double[] closes = bars.stream().mapToDouble(Kline::close).toArray();
        int i = closes.length - 1;
        double px = todayBar != null ? todayBar.open() : d.close();
        String execDay = today.toString();
        store.putMetaIfAbsent("start_day", execDay);
        store.putMetaIfAbsent("baseline_btc", fmt(MaTrendRules.CAPITAL / px * (1 - MaTrendRules.FEE)));
        store.putMeta("signal_day", signalDay);
        store.putMeta("signal_close", fmt(d.close()));
        store.putMeta("exec_day", execDay);
        store.putMeta("exec_price", fmt(px));
        store.putMeta("exec_price_source", todayBar != null ? "D+1 开盘价" : "D 收盘价（D+1 那根暂缺）");
        for (int n : MaTrendRules.MAS) {
            Double m = MaTrendRules.sma(closes, i, n);
            store.putMeta("sma" + n, m == null ? "" : fmt(m));
        }

        List<String> actions = new ArrayList<>();
        for (Slot s : slots) {
            if (s.lastSignalDay() != null && s.lastSignalDay().compareTo(signalDay) >= 0) continue;
            Action a = MaTrendRules.signal(closes, i, s.sma(), s.holding());
            Slot next = new Slot(s.sma(), s.state(), s.usdt(), s.btc(), s.cost(), signalDay);
            if (a == Action.BUY) {
                TrendExecutor.Fill f = executor.buy(s.usdt(), px);
                next = new Slot(s.sma(), State.BTC, 0, f.btc(), s.usdt(), signalDay);
                store.insertTrade(new BtcTrendStore.TradeRow(signalDay, execDay, s.sma(), "BUY", f.price(), f.usdt(), f.btc(),
                        f.fee(), executor.mode()), now);
                actions.add(String.format(Locale.ROOT, "SMA%d 买入 %.6f BTC @ %s（花 %s U）", s.sma(), f.btc(), money(px), money(f.usdt())));
            } else if (a == Action.SELL) {
                TrendExecutor.Fill f = executor.sell(s.btc(), px);
                double pnl = f.usdt() - s.cost();
                next = new Slot(s.sma(), State.USDT, f.usdt(), 0, 0, signalDay);
                store.insertTrade(new BtcTrendStore.TradeRow(signalDay, execDay, s.sma(), "SELL", f.price(), f.usdt(), f.btc(),
                        f.fee(), executor.mode()), now);
                actions.add(String.format(Locale.ROOT, "SMA%d 卖出 %.6f BTC @ %s（到手 %s U，本轮 %s%s U）", s.sma(), f.btc(),
                        money(px), money(f.usdt()), pnl >= 0 ? "+" : "", money(pnl)));
            }
            store.save(next, now);
        }
        return new Outcome(Status.DONE, signalDay, actions, text(true, actions));
    }

    /** 每日推送（daily=true）或 /trend 查询的正文。 */
    public String text(boolean daily, List<String> actions) {
        List<Slot> slots = store.slots();
        if (slots.isEmpty()) return "📈 BTC 均线趋势（影子）\n还没有开始运行（每天越南时间 07:05 第一次运行）。";
        double price = num(store.meta("last_price").orElse(null));
        double close = num(store.meta("signal_close").orElse(null));
        List<String> lines = new ArrayList<>();
        lines.add("📈 BTC 均线趋势（影子，只算不下单）" + (daily ? " " + store.meta("exec_day").orElse("") : ""));
        lines.add("信号日 " + store.meta("signal_day").orElse("?") + " 收盘 " + money(close) + "（Bybit BTC/USDT 现货日线）");
        lines.add("执行价 " + store.meta("exec_price").map(v -> money(num(v))).orElse("?") + "（"
                + store.meta("exec_price_source").orElse("?") + "）");
        lines.add("");
        lines.add("均线 · 买入线（×1.02）· 卖出线（×0.98）· 状态 · 数量 · 成本 · 当前价值");
        double total = 0;
        for (Slot s : slots) {
            double m = num(store.meta("sma" + s.sma()).orElse(null));
            double value = s.holding() ? s.btc() * price : s.usdt();
            total += value;
            lines.add(String.format(Locale.ROOT, "SMA%d %s · %s · %s · %s · %s · %s · %s", s.sma(), money(m),
                    money(m * (1 + MaTrendRules.BAND)), money(m * (1 - MaTrendRules.BAND)),
                    s.holding() ? "BTC" : "USDT",
                    s.holding() ? String.format(Locale.ROOT, "%.6f BTC", s.btc()) : money(s.usdt()) + " U",
                    s.holding() ? money(s.cost()) : "-", money(value)));
        }
        lines.add("");
        if (daily) lines.add("今天动作：" + (actions.isEmpty() ? "不动" : String.join("；", actions)));
        double base = num(store.meta("baseline_btc").orElse(null)) * price;
        lines.add(String.format(Locale.ROOT, "总价值 %s U（本金 %s，%+.2f%%）", money(total), money(MaTrendRules.CAPITAL),
                (total / MaTrendRules.CAPITAL - 1) * 100));
        if (base > 0) {
            lines.add(String.format(Locale.ROOT, "一直拿着（%s 起全部买入持有）%s U，差距 %s%s U", store.meta("start_day").orElse("?"),
                    money(base), total - base >= 0 ? "+" : "", money(total - base)));
        }
        lines.add("现价按 " + money(price) + " 估值。");
        return String.join("\n", lines);
    }

    /** 状态摘要用的一行：各份状态和总价值。 */
    public String summaryLine() {
        List<Slot> slots = store.slots();
        if (slots.isEmpty()) return "未开始";
        double price = num(store.meta("last_price").orElse(null));
        double total = 0;
        List<String> parts = new ArrayList<>();
        for (Slot s : slots) {
            total += s.holding() ? s.btc() * price : s.usdt();
            parts.add("SMA" + s.sma() + " " + s.state());
        }
        return String.join("，", parts) + String.format(Locale.ROOT, "；总价值 %s U", money(total));
    }

    static double num(String s) {
        try {
            return s == null || s.isBlank() ? Double.NaN : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static String fmt(double v) {
        return Double.toString(v);
    }

    static String money(double v) {
        return Double.isNaN(v) ? "?" : String.format(Locale.ROOT, "%,.2f", v);
    }
}
