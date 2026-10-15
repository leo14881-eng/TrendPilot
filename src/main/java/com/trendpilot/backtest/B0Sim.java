package com.trendpilot.backtest;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.trendpilot.capital.Action;
import com.trendpilot.capital.CapitalAllocator;
import com.trendpilot.capital.CapitalReviewer;
import com.trendpilot.capital.ReviewInput;
import com.trendpilot.capital.ReviewResult;
import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.monitor.ExitPolicy;
import com.trendpilot.portfolio.Position;
import com.trendpilot.risk.MarketEvidence;
import com.trendpilot.risk.RiskRecovery;
import com.trendpilot.risk.RiskState;
import com.trendpilot.risk.SystemRiskAssessor;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;

/**
 * 现行 V2（B0）：买入审核、资金分配、系统风险、恢复期、退出规则全部调用线上同一套类，只把盘口换成按成交额估算的合成盘口。
 * 利润保护按 FastWatch 的实时口径：价格下穿保护底线时按底线对应的价格卖出（跳空则按开盘价）。
 */
public final class B0Sim extends Sim {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final double LIQUIDITY_SPREAD_BPS = 5;

    private final CapitalReviewer reviewer;
    private final CapitalAllocator allocator;
    private final ExitPolicy exitPolicy;
    private final SystemRiskAssessor assessor;
    private final RiskRecovery recovery;

    private final Map<String, Position> positions = new LinkedHashMap<>();
    private final Map<String, Double> markNet = new HashMap<>();
    private final Map<String, Long> lastObsHour = new HashMap<>();
    private final Map<String, Path> paths = new HashMap<>();
    private final Map<String, Integer> entryQ = new HashMap<>();
    private final Map<String, Integer> lastExitQ = new HashMap<>();
    private final Map<String, double[]> earlySeen = new HashMap<>(); // firstPrice, firstQ, lastQ
    private final List<Integer> lossExits = new ArrayList<>();

    public B0Sim(String name, MarketData data, Regimes regimes, SignalTape tape, StrategyParams params, int fromQ, int toQ) {
        super(name, data, regimes, tape, params, fromQ, toQ);
        this.reviewer = new CapitalReviewer(params);
        this.allocator = new CapitalAllocator(params);
        this.exitPolicy = new ExitPolicy(params);
        this.assessor = new SystemRiskAssessor(params);
        this.recovery = new RiskRecovery(new MemoryState(), params);
    }

    @Override
    protected void manage(int q) {
        for (Position pos : new ArrayList<>(positions.values())) {
            Series s = data.get(pos.symbol());
            if (!s.has(q)) {
                if (q > s.lastIndex()) delist(pos, q);
                continue;
            }
            double vol = s.volume(q - 1, MarketData.DAY);
            EarlySignal sig = tape.at(pos.symbol(), q - 1);
            SymbolBars bars = sig == null ? null : data.bars(pos.symbol(), q - 1);
            long hour = bars == null ? -1 : bars.hourly().getLast().openTime();
            boolean fresh = sig != null && hour > lastObsHour.getOrDefault(pos.symbol(), Long.MIN_VALUE);
            Path path = paths.get(pos.symbol());
            path.bar(s.high[q], s.low[q]);
            double[] pts = {s.open[q], s.low[q], s.high[q], s.close[q]};
            Position cur = pos;
            double prev = s.open[q];
            for (int k = 0; k < pts.length; k++) {
                double x = pts[k];
                // FastWatch：下穿保护底线时按底线价格卖出，而不是按这根 K 线的最低价
                if (k > 0 && cur.protectArmed() && x < prev) {
                    double c = crossing(cur, vol, cur.protectFloor());
                    if (c < prev && c > x) x = c * (1 - 1e-9);
                }
                ExitDecision d = exitPolicy.evaluate(cur, info(cur.symbol()), Costs.book(cur.symbol(), x, vol, now(q)),
                        sig, fresh && k == 0);
                path.net(d.netPnl().doubleValue());
                if (d.sell()) {
                    close(cur, q, d.fill(), d.netPnl().doubleValue(), d.reason());
                    cur = null;
                    break;
                }
                cur = d.updated();
                markNet.put(cur.symbol(), d.netPnl().doubleValue());
                prev = pts[k];
            }
            if (cur != null) {
                positions.put(cur.symbol(), cur);
                if (fresh) lastObsHour.put(cur.symbol(), hour);
            }
        }
    }

    /** 可退出净利等于 target 时的价格。 */
    private double crossing(Position p, double vol, BigDecimal target) {
        double i = Costs.impactBps(vol) / 10_000;
        return (target.doubleValue() + p.costBasis().doubleValue())
                / (p.qty().doubleValue() * (1 - i) * (1 - params.feeRate()));
    }

    private static SymbolInfo info(String symbol) {
        return new SymbolInfo(symbol, symbol.replace("USDT", ""), "USDT", true, "");
    }

    private void delist(Position pos, int q) {
        Series s = data.get(pos.symbol());
        double last = s.close[s.lastIndex()];
        double proceeds = pos.qty().doubleValue() * last * (1 - 0.005) * (1 - params.feeRate());
        close(pos, q, null, proceeds - pos.costBasis().doubleValue(), "DELISTED");
    }

    private void close(Position pos, int q, Fill fill, double pnl, String reason) {
        positions.remove(pos.symbol());
        markNet.remove(pos.symbol());
        lastObsHour.remove(pos.symbol());
        realized += pnl;
        if (pnl < 0) lossExits.add(q);
        lastExitQ.put(pos.symbol(), q);
        Path p = paths.remove(pos.symbol());
        int eq = entryQ.remove(pos.symbol());
        Series s = data.get(pos.symbol());
        double avg = pos.avgEntryPrice().doubleValue();
        trades.add(new Trade(pos.symbol(), eq, q, pos.costBasis().doubleValue(), pnl, reason, regimes.at(eq), 80,
                pos.tranches(), Math.max(p.maxNet, pnl), Math.min(p.minNet, pnl), p.maeBeforePeak(), p.mfe(),
                Double.NaN, atrDaily(s, eq), pnl < 0 && recovers(s, q, avg * (1 + 2 * params.feeRate())), true));
    }

    @Override
    protected void discover(int q) {
        if (q < 7 * MarketData.DAY) return;
        Instant now = now(q);
        String gen = gen(q);
        List<String> universe = data.universe(q);
        SymbolBars btcBars = data.bars(MarketData.BTC, q);
        Ticker24h btcTicker = data.ticker(MarketData.BTC, q);
        if (btcBars == null || btcTicker == null) return;

        Map<String, EarlySignal> signals = new LinkedHashMap<>();
        List<Ticker24h> tickers = new ArrayList<>();
        tickers.add(btcTicker);
        for (String sym : universe) {
            Ticker24h t = data.ticker(sym, q);
            if (t != null) tickers.add(t);
            EarlySignal s = tape.at(sym, q);
            if (s != null) signals.put(sym, s);
        }
        for (String sym : positions.keySet()) {
            if (!signals.containsKey(sym)) {
                EarlySignal s = tape.at(sym, q);
                if (s != null) signals.put(sym, s);
            }
        }
        signals.values().stream().filter(EarlySignal::early).forEach(s -> logEarly(s.symbol(), q));

        // 系统风险：亏损冻结 = 24h 内亏损退出 + 深度浮亏持仓；冲击、山寨崩盘熔断、恢复期都用线上的类
        lossExits.removeIf(x -> x < q - MarketData.DAY);
        int underwater = (int) positions.values().stream()
                .filter(p -> markNet.getOrDefault(p.symbol(), 0.0) <= -p.costBasis().doubleValue() * params.risk().underwaterLossPct() / 100)
                .count();
        double median1h = data.context(q + 1) == null ? Double.NaN : data.context(q + 1).alt1h();
        RiskRecovery.AltCrash crash = recovery.altCrash(median1h, now);
        MarketEvidence ev = new MarketEvidence(LIQUIDITY_SPREAD_BPS, 0, recovery.confirmedShocks(now), crash.active());
        RiskState raw = assessor.assess(btcBars, btcTicker, tickers, lossExits.size() + underwater, ev);
        recovery.recordShockIfAny(raw, now);
        RiskState risk = recovery.apply(raw, gen);

        List<ReviewResult> approved = new ArrayList<>();
        for (EarlySignal s : signals.values()) {
            String sym = s.symbol();
            Position pos = positions.get(sym);
            boolean candidate = (s.early() && s.score() >= params.review().minScore()) || pos != null;
            if (s.early()) touchEarlySeen(sym, s.price(), q);
            if (!candidate) continue;
            Ticker24h t = data.ticker(sym, q);
            if (t == null) continue;
            Integer exitQ = lastExitQ.get(sym);
            Instant lastExit = exitQ == null ? null : now(exitQ);
            int earlySince = exitQ == null ? 0 : earlySince(sym, exitQ, q);
            boolean postExitHit = exitQ == null
                    || !EarlySignalCalculator.hitsAfter(data.bars(sym, q), s, Instant.ofEpochMilli(data.time(exitQ) + Series.Q)).isEmpty();
            double[] seen = earlySeen.get(sym);
            OrderBook book = Costs.book(sym, data.get(sym).close[q], t.quoteVolume(), now);
            ReviewInput in = new ReviewInput(gen, Instant.ofEpochMilli(data.time(q)), now, true, s, t, book, risk,
                    seen == null ? null : BigDecimal.valueOf(seen[0]), pos, lastExit, verified(sym), earlySince, postExitHit);
            ReviewResult r = reviewer.review(in);
            if (r.approved()) approved.add(r);
        }
        double unrealized = markNet.values().stream().mapToDouble(Double::doubleValue).sum();
        double deployed = positions.values().stream().mapToDouble(p -> p.costBasis().doubleValue()).sum();
        CapitalAllocator.Allocation alloc = allocator.allocate(approved, risk, deployed, realized, unrealized);
        for (ReviewResult r : alloc.funded()) {
            Fill f = r.fill();
            if (r.action() == Action.BUY) {
                positions.put(r.symbol(), new Position(Venue.BINANCE, r.symbol(), f.qty(), f.totalCost(), f.notional(), 1,
                        f.avgPrice(), now, gen, now, false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false));
                entryQ.put(r.symbol(), q);
                paths.put(r.symbol(), new Path(q, f.avgPrice().doubleValue()));
                markNet.put(r.symbol(), -f.fee().doubleValue() * 2);
            } else {
                Position cur = positions.get(r.symbol());
                positions.put(r.symbol(), new Position(cur.venue(), cur.symbol(), cur.qty().add(f.qty()),
                        cur.costBasis().add(f.totalCost()), cur.entryNotional().add(f.notional()), cur.tranches() + 1,
                        f.avgPrice(), now, gen, cur.openedAt(), false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false));
            }
        }
    }

    /** 线上 Ledger.touchEarlySeen 的同一规则：7 天没出现或首次价早于最近一次卖出时重置首次价。 */
    private void touchEarlySeen(String sym, double price, int q) {
        double[] row = earlySeen.get(sym);
        Integer exit = lastExitQ.get(sym);
        boolean reset = row == null || (q - row[2]) >= 7 * MarketData.DAY || (exit != null && exit > row[1]);
        if (reset) earlySeen.put(sym, new double[]{price, q, q});
        else row[2] = q;
    }

    @Override
    protected double deployedCost() {
        return positions.values().stream().mapToDouble(p -> p.costBasis().doubleValue()).sum();
    }

    @Override
    protected double unrealized(int q) {
        double sum = 0;
        for (Position p : positions.values()) {
            Series s = data.get(p.symbol());
            double c = s.lastClose(q, 400);
            if (Double.isNaN(c)) continue;
            double i = Costs.impact(s.volume(q, MarketData.DAY), p.costBasis().doubleValue());
            sum += p.qty().doubleValue() * c * (1 - i) * (1 - params.feeRate()) - p.costBasis().doubleValue();
        }
        return sum;
    }

    @Override
    protected void closeAll(int q) {
        for (Position p : new ArrayList<>(positions.values())) {
            Series s = data.get(p.symbol());
            double c = s.lastClose(q, 100_000);
            double i = Costs.impact(s.volume(q, MarketData.DAY), p.costBasis().doubleValue());
            double pnl = p.qty().doubleValue() * c * (1 - i) * (1 - params.feeRate()) - p.costBasis().doubleValue();
            close(p, q, null, pnl, "END");
        }
    }

}
