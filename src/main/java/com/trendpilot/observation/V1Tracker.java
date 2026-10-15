package com.trendpilot.observation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.BarsLoader;
import com.trendpilot.market.ExecutionSimulator;
import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.Markets;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.monitor.ExitPolicy;
import com.trendpilot.notify.Messages;
import com.trendpilot.portfolio.ObservationBook;
import com.trendpilot.portfolio.Position;
import com.trendpilot.research.Identity;
import com.trendpilot.risk.RiskState;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.universe.UniverseFilter;

/**
 * V1 宽口径观察账本。
 * <ul>
 * <li>进入：EARLY 至少两个条件、所在池扫描完整、系统风险允许新增、没有明确身份错误（黑名单、身份歧义）、该币当前没有 V1 观察、
 *     距上次 V1 退出至少一个再入场冷却期。没有评分门槛、资金池和数量上限。</li>
 * <li>记账：每笔名义 1,000U，按参考价 + 手续费记账（不请求盘口），样本收益不能当作可执行组合收益。</li>
 * <li>退出：与 V2 相同的退出顺序（硬失效 → 利润保护 → 论点失效 → 8% 复核），价格取一次批量的 24h 行情。</li>
 * </ul>
 * 每轮的 V1 进入与退出各汇总成一条 Telegram，避免刷屏。
 */
@Component
public class V1Tracker {

    private static final Logger log = LoggerFactory.getLogger(V1Tracker.class);
    /** V1 买入/卖出通知只列前 20 个，其余只给数量（全部明细在网页和数据库里）。 */
    static final int NOTIFY_TOP = 20;

    private final ObservationBook book;
    private final Markets markets;
    private final EarlySignalCalculator signals;
    private final ExitPolicy exitPolicy;
    private final StrategyParams params;

    public V1Tracker(ObservationBook book, Markets markets, EarlySignalCalculator signals, ExitPolicy exitPolicy,
                     StrategyParams params) {
        this.book = book;
        this.markets = markets;
        this.signals = signals;
        this.exitPolicy = exitPolicy;
        this.params = params;
    }

    /** Discovery 中的一个 EARLY 信号。 */
    public record Candidate(Venue venue, EarlySignal signal, Ticker24h ticker, boolean scanComplete, Identity identity) {
    }

    /** @return 本轮新进入 V1 的标的 */
    public List<String> enter(String gen, Instant now, List<Candidate> early, RiskState risk) {
        List<String> entered = new ArrayList<>();
        List<Map.Entry<Double, String>> ranked = new ArrayList<>();
        if (!risk.newRiskAllowed()) {
            return entered; // 系统风险不允许新增时 V1 也不进入
        }
        for (Candidate c : early) {
            EarlySignal s = c.signal();
            if (!s.early() || !c.scanComplete() || c.ticker() == null) continue;
            // 明确的身份错误也不进 V1；身份资料缺失不算错误
            if (params.universe().blocklist().contains(s.symbol()) || c.identity() == Identity.AMBIGUOUS) continue;
            if (book.position(s.symbol()).isPresent()) continue;
            Instant lastExit = book.lastExit(s.symbol()).orElse(null);
            if (lastExit != null && Duration.between(lastExit, now).toMinutes() < params.review().reentryCooldownMinutes()) {
                continue;
            }
            OrderBook ref = OrderBook.reference(s.symbol(), c.ticker().lastPrice(), now);
            Fill fill = ExecutionSimulator.buy(ref, BigDecimal.valueOf(params.capital().tradeSize()), params.feeRate());
            book.open(c.venue(), s.symbol(), fill, gen, now);
            entered.add(s.symbol());
            ranked.add(Map.entry(s.score(), String.format(Locale.ROOT, "· %s%s 价格 %s，评分 %.1f，命中 %s", s.symbol(),
                    Messages.venueTag(c.venue()), Messages.price(fill.avgPrice()), s.score(), String.join("/", s.hits()))));
        }
        if (!ranked.isEmpty()) {
            // 用户要求：V1 不发 Telegram（每天上千条），只记日志和数据库；V1/V2 对比在每日汇总里
            log.info("V1 观察买入 {} 个 {}\n{}", ranked.size(), gen, Messages.top(ranked, NOTIFY_TOP, "评分最高"));
        }
        return entered;
    }

    public void recordEquity(Instant now) {
        book.recordEquity(now);
    }

    /** 当前全部 V1 观察仓位。 */
    public List<Position> positions() {
        return book.positions();
    }

    /** 一个交易所本轮的行情输入（锁外抓取）。 */
    public record VenueInputs(Map<String, Ticker24h> prices, BarsLoader.Result bars, Map<String, SymbolInfo> infos) {
    }

    /** 抓取管理 V1 持仓需要的行情（联网，在组合锁外调用）。某个交易所失败只跳过它。 */
    public Map<Venue, VenueInputs> fetch(Instant now) {
        Map<Venue, VenueInputs> out = new EnumMap<>(Venue.class);
        Map<Venue, List<Position>> byVenue = book.positions().stream().collect(Collectors.groupingBy(Position::venue,
                () -> new EnumMap<>(Venue.class), Collectors.toList()));
        for (var e : byVenue.entrySet()) {
            VenueMarket m = markets.get(e.getKey());
            if (m == null) continue;
            try {
                Map<String, Ticker24h> prices = m.tickers().tickers24h().stream()
                        .collect(Collectors.toMap(Ticker24h::symbol, Function.identity(), (a, b) -> a));
                List<String> names = new ArrayList<>(e.getValue().stream().map(Position::symbol).toList());
                names.add(UniverseFilter.BTC);
                BarsLoader.Result bars = m.bars().load(names, now);
                Map<String, SymbolInfo> infos = new java.util.HashMap<>();
                for (Position pos : e.getValue()) {
                    SymbolInfo info = m.directory().get(pos.symbol());
                    if (info != null) infos.put(pos.symbol(), info);
                }
                out.put(e.getKey(), new VenueInputs(prices, bars, infos));
            } catch (RuntimeException ex) {
                log.warn("V1 本轮跳过 {}：行情获取失败 {}", e.getKey(), ex.getMessage()); // 不用旧价格做决定
            }
        }
        return out;
    }

    /** Monitor 每 5 分钟调用：按退出顺序管理全部 V1 观察。@return 本轮退出的标的 */
    public List<String> manage(String gen, Instant now) {
        return manage(gen, now, fetch(now));
    }

    /** 只用已抓好的输入做判断和记账（不联网，在组合锁内调用）。 */
    public List<String> manage(String gen, Instant now, Map<Venue, VenueInputs> inputs) {
        List<Position> positions = book.positions();
        if (positions.isEmpty()) return List.of();
        List<String> exits = new ArrayList<>();
        List<Map.Entry<Double, String>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Position pos : positions) {
            VenueInputs in = inputs.get(pos.venue());
            if (in == null) continue;
            try {
                Ticker24h t = in.prices().get(pos.symbol());
                if (t == null || !(t.lastPrice() > 0)) continue; // 价格缺失：本轮不判断
                SymbolBars btc = in.bars().bars().get(UniverseFilter.BTC);
                SymbolBars b = in.bars().bars().get(pos.symbol());
                EarlySignal sig = (b == null || btc == null) ? null : signals.compute(b, btc);
                long hour = sig == null ? -1 : b.hourly().getLast().openTime();
                boolean fresh = sig != null && book.isNewObservation(pos.symbol(), hour);
                ExitDecision d = exitPolicy.evaluate(pos, in.infos().get(pos.symbol()),
                        OrderBook.reference(pos.symbol(), t.lastPrice(), now), sig, fresh);
                if (fresh && !d.sell()) book.markObserved(pos.symbol(), hour);
                for (String note : d.notes()) {
                    if (!note.equals("PROTECTION_GAPPED")) book.note(pos, note, gen, now);
                }
                if (d.sell()) {
                    book.close(pos, d.fill(), d.netPnl(), d.reason(), gen, now);
                    exits.add(pos.symbol());
                    total = total.add(d.netPnl());
                    lines.add(Map.entry(d.netPnl().abs().doubleValue(), String.format(Locale.ROOT,
                            "· %s%s %s，净盈亏 %s U（%.2f%%）", pos.symbol(),
                            Messages.venueTag(pos.venue()), d.reason(), Messages.money(d.netPnl()), d.rawReturnPct())));
                } else {
                    book.update(d.updated(), d.netPnl(), now);
                }
            } catch (RuntimeException ex) {
                log.warn("V1 {} 本轮判断出错，跳过：{}", pos.symbol(), ex.toString()); // 一个持仓出错不影响其他
            }
        }
        if (!lines.isEmpty()) {
            log.info("V1 观察卖出 {} 个 {}，合计净盈亏 {} U\n{}", lines.size(), gen, Messages.money(total),
                    Messages.top(lines, NOTIFY_TOP, "盈亏金额最大"));
        }
        return exits;
    }
}
