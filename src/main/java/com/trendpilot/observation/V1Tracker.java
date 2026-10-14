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
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.monitor.ExitPolicy;
import com.trendpilot.notify.Messages;
import com.trendpilot.notify.Notifier;
import com.trendpilot.portfolio.ObservationBook;
import com.trendpilot.portfolio.Position;
import com.trendpilot.risk.RiskState;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.universe.UniverseFilter;

/**
 * V1 宽口径观察账本。
 * <ul>
 * <li>进入：EARLY 至少两个条件、所在池扫描完整、系统风险允许新增、不在黑名单、该币当前没有 V1 观察、
 *     距上次 V1 退出至少一个再入场冷却期。没有评分门槛、资金池和数量上限。</li>
 * <li>记账：每笔名义 1,000U，按参考价 + 手续费记账（不请求盘口），样本收益不能当作可执行组合收益。</li>
 * <li>退出：与 V2 相同的退出顺序（硬失效 → 利润保护 → 论点失效 → 8% 复核），价格取一次批量的 24h 行情。</li>
 * </ul>
 * 每轮的 V1 进入与退出各汇总成一条 Telegram，避免刷屏。
 */
@Component
public class V1Tracker {

    private static final Logger log = LoggerFactory.getLogger(V1Tracker.class);

    private final ObservationBook book;
    private final Markets markets;
    private final EarlySignalCalculator signals;
    private final ExitPolicy exitPolicy;
    private final Notifier notifier;
    private final StrategyParams params;

    public V1Tracker(ObservationBook book, Markets markets, EarlySignalCalculator signals, ExitPolicy exitPolicy,
                     Notifier notifier, StrategyParams params) {
        this.book = book;
        this.markets = markets;
        this.signals = signals;
        this.exitPolicy = exitPolicy;
        this.notifier = notifier;
        this.params = params;
    }

    /** Discovery 中的一个 EARLY 信号。 */
    public record Candidate(Venue venue, EarlySignal signal, Ticker24h ticker, boolean scanComplete) {
    }

    /** @return 本轮新进入 V1 的标的 */
    public List<String> enter(String gen, Instant now, List<Candidate> early, RiskState risk) {
        List<String> entered = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        if (!risk.newRiskAllowed()) {
            return entered; // 系统风险不允许新增时 V1 也不进入
        }
        for (Candidate c : early) {
            EarlySignal s = c.signal();
            if (!s.early() || !c.scanComplete() || c.ticker() == null) continue;
            if (params.universe().blocklist().contains(s.symbol())) continue;
            if (book.position(s.symbol()).isPresent()) continue;
            Instant lastExit = book.lastExit(s.symbol()).orElse(null);
            if (lastExit != null && Duration.between(lastExit, now).toMinutes() < params.review().reentryCooldownMinutes()) {
                continue;
            }
            OrderBook ref = OrderBook.reference(s.symbol(), c.ticker().lastPrice(), now);
            Fill fill = ExecutionSimulator.buy(ref, BigDecimal.valueOf(params.capital().tradeSize()), params.feeRate());
            book.open(c.venue(), s.symbol(), fill, gen, now);
            entered.add(s.symbol());
            lines.add(String.format(Locale.ROOT, "· %s%s 价格 %s，评分 %.1f，命中 %s", s.symbol(),
                    Messages.venueTag(c.venue()), Messages.price(fill.avgPrice()), s.score(), String.join("/", s.hits())));
        }
        if (!lines.isEmpty()) {
            notifier.send("v1-entry:" + gen, "🟢 V1 观察买入 " + lines.size() + " 个（每个名义 "
                    + (int) params.capital().tradeSize() + " U，参考价记账，不占 V2 资金）\n"
                    + "原因：EARLY 至少命中 " + params.early().minHits() + " 个条件\n" + Messages.lines(lines));
        }
        return entered;
    }

    /** Monitor 每 5 分钟调用：按退出顺序管理全部 V1 观察。@return 本轮退出的标的 */
    public List<String> manage(String gen, Instant now) {
        List<Position> positions = book.positions();
        if (positions.isEmpty()) return List.of();
        Map<Venue, List<Position>> byVenue = positions.stream().collect(Collectors.groupingBy(Position::venue,
                () -> new EnumMap<>(Venue.class), Collectors.toList()));
        List<String> exits = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (var e : byVenue.entrySet()) {
            VenueMarket m = markets.get(e.getKey());
            if (m == null) continue;
            Map<String, Ticker24h> prices;
            try {
                prices = m.source().tickers24h().stream()
                        .collect(Collectors.toMap(Ticker24h::symbol, Function.identity(), (a, b) -> a));
            } catch (RuntimeException ex) {
                log.warn("V1 本轮跳过 {}：行情获取失败 {}", e.getKey(), ex.getMessage()); // 不用旧价格做决定
                continue;
            }
            List<String> names = new ArrayList<>(e.getValue().stream().map(Position::symbol).toList());
            names.add(UniverseFilter.BTC);
            BarsLoader.Result bars = m.bars().load(names, now);
            SymbolBars btc = bars.bars().get(UniverseFilter.BTC);
            for (Position pos : e.getValue()) {
                Ticker24h t = prices.get(pos.symbol());
                if (t == null || t.lastPrice() <= 0) continue; // 价格缺失：本轮不判断
                SymbolBars b = bars.bars().get(pos.symbol());
                EarlySignal sig = (b == null || btc == null) ? null : signals.compute(b, btc);
                ExitDecision d = exitPolicy.evaluate(pos, m.directory().get(pos.symbol()),
                        OrderBook.reference(pos.symbol(), t.lastPrice(), now), sig);
                for (String note : d.notes()) {
                    if (!note.equals("PROTECTION_GAPPED")) book.note(pos, note, gen, now);
                }
                if (d.sell()) {
                    book.close(pos, d.fill(), d.netPnl(), d.reason(), gen, now);
                    exits.add(pos.symbol());
                    lines.add(String.format(Locale.ROOT, "· %s%s %s，净盈亏 %s U（%.2f%%）", pos.symbol(),
                            Messages.venueTag(pos.venue()), d.reason(), Messages.money(d.netPnl()), d.rawReturnPct()));
                } else {
                    book.update(d.updated(), d.netPnl(), now);
                }
            }
        }
        if (!lines.isEmpty()) {
            notifier.send("v1-exit:" + gen, "🔵 V1 观察卖出 " + lines.size() + " 个（参考价记账）\n" + Messages.lines(lines));
        }
        return exits;
    }
}
