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
    /** 冻结修复（设计文档 7.7 第 2 条）：深度浮亏只在第一次跌破线后的窗口内计一次，之后不再计入。 */
    private final boolean freezeFix;
    private final Map<String, Integer> underwaterSince = new HashMap<>();
    /** 到期离场：持有满这么多小时、利润保护还没启动，就按市价卖（0 = 不用）。 */
    private int timeExitHours;

    B0Sim timeExit(int hours) {
        this.timeExitHours = hours;
        return this;
    }

    /** 只在这些行情状态下开新仓（加仓不受限）；null = 不限（盈亏不对称研究，2026-10-23）。 */
    private java.util.Set<com.trendpilot.risk.Regime> buyRegimes;

    B0Sim buyOnlyIn(java.util.Set<com.trendpilot.risk.Regime> regimes) {
        this.buyRegimes = regimes;
        return this;
    }

    /** 买入端研究（EntryStudy）：开仓时的特征，key = symbol@entryQ。 */
    final Map<String, EntryFeat> entryFeats = new HashMap<>();
    /** 买入过滤：只对新开仓生效，false 就不买；null = 不过滤。 */
    private java.util.function.Predicate<EntryFeat> entryFilter;
    /** 随机对照：现行规则决定"这一轮买几笔、每笔多少钱"，但买的币换成同币池里随机一个没持有的币；null = 不用。 */
    private java.util.Random random;

    B0Sim entryFilter(java.util.function.Predicate<EntryFeat> filter) {
        this.entryFilter = filter;
        return this;
    }

    /** 配置化变体的参数改动（审核门槛、H1 追高过滤、H4 放量上限、H2 提前离场）；null = 与现行相同。和线上变体账本是同一份判断代码。 */
    private com.trendpilot.variant.VariantOverrides overrides;

    B0Sim overrides(com.trendpilot.variant.VariantOverrides o) {
        this.overrides = o == null || o.isEmpty() ? null : o;
        if (overrides != null && overrides.isMomentum()) momentum(overrides.momentumTop(), overrides.momentumHoursOr24() * 4);
        this.runnerPolicy = overrides != null && overrides.hasRunner()
                ? new ExitPolicy(com.trendpilot.variant.VariantRules.runnerParams(params, overrides)) : null;
        return this;
    }

    /**
     * 实时涨幅榜动量（用户 2026-10-26："为什么不能买入涨幅榜前几名"）：每次审核按当时已知的滚动涨幅（24h 或 4h）取池内前 N 名，
     * 没持有、盘口深度和滑点过审核门槛就按 V2 的每笔金额买入；资金分配、亏损冻结、行情上限照 V2。不看 EARLY 信号。0 = 不用。
     */
    private int momentumTopN;
    private int momentumLookback = MarketData.DAY;

    B0Sim momentum(int topN, int lookbackQuarters) {
        this.momentumTopN = topN;
        this.momentumLookback = lookbackQuarters;
        return this;
    }

    /** 跟踪止损：1h 收盘低于持仓以来最高价 ×(1 − k × 买入时日线 ATR%) 就卖；0 = 不用。 */
    private double trailAtrK;

    B0Sim trailAtr(double k) {
        this.trailAtrK = k;
        return this;
    }

    /** "让赢家跑"阶段用的宽回吐 ExitPolicy；null = 不用。 */
    private ExitPolicy runnerPolicy;

    /** 买入审核的频率（15m 根数）：默认 4 = 每小时一次（线上 :17）；1 = 每 15 分钟（买入延迟研究，只在回测里）。 */
    B0Sim discoverEvery(int quarters) {
        this.discoverEvery = quarters;
        return this;
    }

    /** 大盘过滤（BTC 主动买卖研究 2026-10-30）：Discovery 第 q 根时返回 false 就不开新仓（加仓不受限）；null = 不过滤。 */
    private java.util.function.IntPredicate buyGate;

    B0Sim buyGate(java.util.function.IntPredicate gate) {
        this.buyGate = gate;
        return this;
    }

    B0Sim randomEntry(long seed) {
        this.random = new java.util.Random(seed);
        return this;
    }

    private EntryFeat feat(String sym, int q, EarlySignal s, double quoteVol, RiskState risk) {
        return EntryFeat.of(data, regimes, sym, q, s, quoteVol, risk.regime().name(), atrDaily(data.get(sym), q));
    }

    /** 防洗盘止损研究（StopStudy）：叠加在现行退出规则之上的止损；null = 不止损。 */
    private StopRule stopRule;
    /** 每个持仓买入时算好的止损距离（比例）。 */
    private final Map<String, Double> stopDist = new HashMap<>();

    B0Sim stop(StopRule rule) {
        this.stopRule = rule;
        return this;
    }

    /**
     * 不赚钱就走（QuickExitStudy，用户 2026-10-17 提议）：持有满 staleHours、利润保护没启动、按 1h 收盘仍亏损，
     * waitBounce=false 时当根收盘卖；true 时进入"等反弹"：之后某根 1h 收盘回本就卖，或反弹过后第一根回落的 1h 收盘卖（局部高点），
     * 最多再等 maxWaitHours 小时按收盘卖。
     */
    private int staleHours;
    private boolean staleWaitBounce;
    private int staleMaxWaitHours;
    /** symbol → {进入等待的 q, 上一根 1h 收盘价, 等待期间是否出现过上涨的 1h 收盘(0/1)} */
    private final Map<String, double[]> staleWait = new HashMap<>();

    B0Sim staleExit(int hours, boolean waitBounce, int maxWaitHours) {
        this.staleHours = hours;
        this.staleWaitBounce = waitBounce;
        this.staleMaxWaitHours = maxWaitHours;
        return this;
    }

    /** 第 q 根（1h 收盘时）是否按"不赚钱就走"卖出；返回原因或 null。只用已收盘数据。 */
    private String staleCheck(Position pos, Series s, int q, double net) {
        if (staleHours <= 0 || q % 4 != 3 || pos.protectArmed()) return null;
        double[] w = staleWait.get(pos.symbol());
        if (w == null) {
            if (java.time.Duration.between(pos.openedAt(), now(q)).toMinutes() < staleHours * 60L || net >= 0) return null;
            if (!staleWaitBounce) return "L1_STALE";
            staleWait.put(pos.symbol(), new double[]{q, s.close[q], 0});
            return null;
        }
        if (net >= 0) return "L1_STALE_EVEN";
        double prev = w[1];
        if (w[2] > 0 && s.close[q] < prev) return "L1_STALE_BOUNCE";
        if (s.close[q] > prev) w[2] = 1;
        w[1] = s.close[q];
        if (q - w[0] >= staleMaxWaitHours * 4L) return "L1_STALE_TIMEOUT";
        return null;
    }

    public B0Sim(String name, MarketData data, Regimes regimes, SignalTape tape, StrategyParams params, int fromQ, int toQ) {
        this(name, data, regimes, tape, params, fromQ, toQ, false);
    }

    public B0Sim(String name, MarketData data, Regimes regimes, SignalTape tape, StrategyParams params, int fromQ, int toQ,
                 boolean freezeFix) {
        super(name, data, regimes, tape, params, fromQ, toQ);
        this.freezeFix = freezeFix;
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
            if (timeExitHours > 0 && !pos.protectArmed()
                    && java.time.Duration.between(pos.openedAt(), now(q)).toMinutes() >= timeExitHours * 60L) {
                double imp = Costs.impactBps(vol) / 10_000;
                double proceeds = pos.qty().doubleValue() * s.open[q] * (1 - imp) * (1 - params.feeRate());
                close(pos, q, null, proceeds - pos.costBasis().doubleValue(), "TIME_EXIT");
                continue;
            }
            if (stopRule != null && pos.costBasis().signum() > 0) {
                String why = stopRule.check(data, s, q, pos.costBasis().doubleValue() / pos.qty().doubleValue(), entryQ.get(pos.symbol()),
                        stopDist.getOrDefault(pos.symbol(), stopRule.fallback()));
                if (why != null) {
                    // 按触发那根 K 线的收盘价卖出（收盘确认），扣冲击和手续费
                    double imp = Costs.impact(vol, pos.costBasis().doubleValue());
                    double proceeds = pos.qty().doubleValue() * s.close[q] * (1 - imp) * (1 - params.feeRate());
                    path.net(proceeds - pos.costBasis().doubleValue());
                    close(pos, q, null, proceeds - pos.costBasis().doubleValue(), why);
                    continue;
                }
            }
            if (trailAtrK > 0 && pos.costBasis().signum() > 0 && q % 4 == 3) {
                double atr = atrDaily(s, entryQ.get(pos.symbol()));
                double dist = Double.isNaN(atr) ? 0.10 : trailAtrK * atr / 100;
                if (s.close[q] <= path.peakHigh * (1 - dist)) {
                    double imp = Costs.impact(vol, pos.costBasis().doubleValue());
                    double net = pos.qty().doubleValue() * s.close[q] * (1 - imp) * (1 - params.feeRate()) - pos.costBasis().doubleValue();
                    path.net(net);
                    close(pos, q, null, net, "TRAIL_ATR");
                    continue;
                }
            }
            if (overrides != null && overrides.hasEarlyExit() && pos.costBasis().signum() > 0 && q % 4 == 3) {
                double avg = pos.costBasis().doubleValue() / pos.qty().doubleValue();
                double ret = (s.close[q] / avg - 1) * 100;
                double imp = Costs.impact(vol, pos.costBasis().doubleValue());
                double net = pos.qty().doubleValue() * s.close[q] * (1 - imp) * (1 - params.feeRate()) - pos.costBasis().doubleValue();
                String why = overrides.extraExit(java.time.Duration.between(pos.openedAt(), now(q)), pos.protectArmed(), path.maxNet,
                        pos.costBasis().doubleValue(), net / pos.costBasis().doubleValue() * 100, ret);
                if (why != null) {
                    path.net(net);
                    close(pos, q, null, net, why);
                    continue;
                }
            }
            if (staleHours > 0 && pos.costBasis().signum() > 0 && q % 4 == 3) {
                double imp = Costs.impact(vol, pos.costBasis().doubleValue());
                double net = pos.qty().doubleValue() * s.close[q] * (1 - imp) * (1 - params.feeRate()) - pos.costBasis().doubleValue();
                String why = staleCheck(pos, s, q, net);
                if (why != null) {
                    path.net(net);
                    close(pos, q, null, net, why);
                    continue;
                }
            }
            Position cur = pos;
            double prev = s.open[q];
            for (int k = 0; k < pts.length; k++) {
                double x = pts[k];
                // FastWatch：下穿保护底线时按底线价格卖出，而不是按这根 K 线的最低价
                if (k > 0 && cur.protectArmed() && x < prev) {
                    double c = crossing(cur, vol, cur.protectFloor());
                    if (c < prev && c > x) x = c * (1 - 1e-9);
                }
                boolean runner = runnerPolicy != null
                        && overrides.runnerActive(cur.protectArmed(), cur.peakNet().doubleValue(), cur.costBasis().doubleValue());
                ExitDecision d = (runner ? runnerPolicy : exitPolicy).evaluate(
                        runner ? com.trendpilot.variant.VariantRules.runnerView(cur) : cur, info(cur.symbol()),
                        Costs.book(cur.symbol(), x, vol, now(q)), sig, fresh && k == 0);
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
        stopDist.remove(pos.symbol());
        staleWait.remove(pos.symbol());
        underwaterSince.remove(pos.symbol());
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
        int underwater;
        if (freezeFix) {
            for (Position p : positions.values()) {
                if (markNet.getOrDefault(p.symbol(), 0.0) <= -p.costBasis().doubleValue() * params.risk().underwaterLossPct() / 100) {
                    underwaterSince.putIfAbsent(p.symbol(), q);
                }
            }
            int window = params.risk().lossWindowHours() * 4;
            underwater = (int) positions.keySet().stream().filter(sym -> {
                Integer since = underwaterSince.get(sym);
                return since != null && q - since < window;
            }).count();
        } else {
            underwater = (int) positions.values().stream()
                    .filter(p -> markNet.getOrDefault(p.symbol(), 0.0) <= -p.costBasis().doubleValue() * params.risk().underwaterLossPct() / 100)
                    .count();
        }
        double median1h = data.context(q + 1) == null ? Double.NaN : data.context(q + 1).alt1h();
        RiskRecovery.AltCrash crash = recovery.altCrash(median1h, now);
        MarketEvidence ev = new MarketEvidence(LIQUIDITY_SPREAD_BPS, 0, recovery.confirmedShocks(now), crash.active());
        RiskState raw = assessor.assess(btcBars, btcTicker, tickers, lossExits.size() + underwater, ev);
        recovery.recordShockIfAny(raw, now);
        RiskState risk = recovery.apply(raw, gen);

        List<ReviewResult> approved = new ArrayList<>();
        if (momentumTopN > 0) {
            approved.addAll(momentumPicks(universe, q, now, risk));
            signals.clear(); // 不走 EARLY 审核
        }
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
            ReviewResult r = overrides == null ? reviewer.review(in)
                    : reviewer.review(in, overrides.minDepthUsdtOr(params.review().minDepthUsdt()),
                            overrides.minBuyRel1hOr(params.review().minBuyRel1h()),
                            overrides.tradeUsdtOr(params.capital().tradeSize()));
            if (overrides != null && r.approved() && r.action() == Action.BUY) {
                double m = overrides.entryMultiplier(risk.btc24hPct(), t.priceChangePct(), s.volumeAccel());
                if (m <= 0) continue;
                if (m < 1) {
                    r = reviewer.review(in, overrides.minDepthUsdtOr(params.review().minDepthUsdt()),
                            overrides.minBuyRel1hOr(params.review().minBuyRel1h()),
                            overrides.tradeUsdtOr(params.capital().tradeSize()) * m);
                    if (!r.approved()) continue;
                }
            }
            if (r.approved() && entryFilter != null && r.action() == Action.BUY
                    && !entryFilter.test(feat(sym, q, s, t.quoteVolume(), risk))) continue;
            if (r.approved() && (buyRegimes == null || r.action() != Action.BUY || buyRegimes.contains(risk.regime()))) approved.add(r);
        }
        if (buyGate != null && !buyGate.test(q)) approved.removeIf(r -> r.action() == Action.BUY);
        double unrealized = markNet.values().stream().mapToDouble(Double::doubleValue).sum();
        double deployed = positions.values().stream().mapToDouble(p -> p.costBasis().doubleValue()).sum();
        CapitalAllocator.Allocation alloc = allocator.allocate(approved, risk, deployed, realized, unrealized);
        for (ReviewResult r : alloc.funded()) {
            Fill f = r.fill();
            if (r.action() == Action.BUY) {
                String sym = r.symbol();
                if (random != null) {
                    sym = randomSymbol(universe, q);
                    if (sym == null) continue;
                    Series rs = data.get(sym);
                    Ticker24h rt = data.ticker(sym, q);
                    f = com.trendpilot.market.ExecutionSimulator.buy(Costs.book(sym, rs.close[q], rt.quoteVolume(), now),
                            f.notional(), params.feeRate());
                    if (f == null || !f.complete() || f.qty().signum() <= 0) continue;
                }
                Ticker24h ft = data.ticker(sym, q);
                entryFeats.put(sym + "@" + q, feat(sym, q, random != null ? tape.at(sym, q) : signals.get(sym),
                        ft == null ? Double.NaN : ft.quoteVolume(), risk));
                positions.put(sym, new Position(Venue.BINANCE, sym, f.qty(), f.totalCost(), f.notional(), 1,
                        f.avgPrice(), now, gen, now, false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false));
                entryQ.put(sym, q);
                if (stopRule != null) stopDist.put(sym, stopRule.distance(atrDaily(data.get(sym), q)));
                paths.put(sym, new Path(q, f.avgPrice().doubleValue()));
                markNet.put(sym, -f.fee().doubleValue() * 2);
            } else {
                Position cur = positions.get(r.symbol());
                positions.put(r.symbol(), new Position(cur.venue(), cur.symbol(), cur.qty().add(f.qty()),
                        cur.costBasis().add(f.totalCost()), cur.entryNotional().add(f.notional()), cur.tranches() + 1,
                        f.avgPrice(), now, gen, cur.openedAt(), false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false));
            }
        }
    }

    /** 涨幅榜前 N 名（按 q 收盘时已知的滚动涨幅），过盘口深度、滑点和系统风险就作为 BUY。 */
    private List<ReviewResult> momentumPicks(List<String> universe, int q, Instant now, RiskState risk) {
        List<ReviewResult> out = new ArrayList<>();
        if (!risk.newRiskAllowed()) return out;
        List<String> ranked = universe.stream().filter(sym -> !sym.equals(MarketData.BTC))
                .filter(sym -> data.get(sym) != null && data.get(sym).has(q) && q - momentumLookback >= 0 && data.get(sym).has(q - momentumLookback))
                .sorted(java.util.Comparator.comparingDouble(sym -> -(data.get(sym).close[q] / data.get(sym).close[q - momentumLookback])))
                .limit(momentumTopN).toList();
        StrategyParams.Review rv = params.review();
        for (String sym : ranked) {
            if (positions.containsKey(sym)) continue;
            Integer exitQ = lastExitQ.get(sym);
            if (exitQ != null && q - exitQ < 4) continue; // 卖出后 1 小时内不买回
            Ticker24h t = data.ticker(sym, q);
            if (t == null) continue;
            OrderBook book = Costs.book(sym, data.get(sym).close[q], t.quoteVolume(), now);
            double depth = Math.min(book.bidDepthWithin(rv.depthBandPct()), book.askDepthWithin(rv.depthBandPct()));
            Fill f = com.trendpilot.market.ExecutionSimulator.buy(book, BigDecimal.valueOf(params.capital().tradeSize()), params.feeRate());
            if (depth < rv.minDepthUsdt() || f == null || !f.complete() || f.slippageBps() > rv.maxSlippageBps()) continue;
            out.add(new ReviewResult(sym, Action.BUY, com.trendpilot.capital.Decision.BUY, List.of(), List.of(), t.priceChangePct(),
                    Double.NaN, book.spreadBps(), f.slippageBps(), depth, depth, f));
        }
        return out;
    }

    private String randomSymbol(List<String> universe, int q) {
        List<String> pool = new ArrayList<>();
        for (String sym : universe) {
            if (sym.equals(MarketData.BTC) || positions.containsKey(sym)) continue;
            Series x = data.get(sym);
            if (x == null || !x.has(q) || data.ticker(sym, q) == null) continue;
            pool.add(sym);
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
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
