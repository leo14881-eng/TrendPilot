package com.trendpilot.backtest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.trendpilot.capital.CapitalAllocator;
import com.trendpilot.capital.CapitalReviewer;
import com.trendpilot.capital.ReviewInput;
import com.trendpilot.capital.ReviewResult;
import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.monitor.ExitPolicy;
import com.trendpilot.risk.MarketEvidence;
import com.trendpilot.risk.RiskRecovery;
import com.trendpilot.risk.RiskState;
import com.trendpilot.risk.SystemRiskAssessor;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.v2n.V2nParams;
import com.trendpilot.v2n.V2nRules;

/**
 * 新版 V2（N）：买入先过线上同一套审核（评分、价差、滑点、RR、追涨、过热、再入场），再过山寨大盘门、绝对趋势门、追高门；
 * 按止损距离定金额；退出用 L1 价格止损 / L2 时间止损 / L3 论点失效 + 保本、部分止盈、ATR 跟踪；只加赚钱的仓位。
 * 路径顺序固定为 开 → 低 → 高 → 收（同一根 K 线先止损后止盈，保守）。
 */
public final class NSim extends Sim {

    private final V2nParams p;
    private final CapitalReviewer reviewer;
    private final CapitalAllocator allocator;
    private final SystemRiskAssessor assessor;
    private final RiskRecovery recovery;
    private final double fee;

    private final Map<String, Pos> positions = new LinkedHashMap<>();
    private final Map<String, Integer> lastExitQ = new HashMap<>();
    private final Map<String, Boolean> lastExitStop = new HashMap<>();
    private final Map<String, List<Integer>> stopTimes = new HashMap<>();
    private final Map<String, Integer> pendingUntil = new HashMap<>();
    private final Map<String, double[]> earlySeen = new HashMap<>();
    private final List<double[]> lossLog = new ArrayList<>(); // [q, pnl]
    private int freezeUntil = -1;

    /** 一个持仓。金额 U，价格为行情价。 */
    static final class Pos {
        String symbol;
        int entryQ;
        double qty, cost, totalCost, realizedPart, firstSize, risk, stopPct, stop, atrPctEntry, atrDailyEntry;
        double peakNet, highestClose, lastTranchePrice;
        boolean breakeven, partial, altOpen;
        int tranches = 1, adds, lastAddQ, weakStreak;
        long lastObsHour = Long.MIN_VALUE;
        // 现行利润保护（exitMode = B0 的消融）
        boolean armed;
        double floor;
        Path path;
    }

    public NSim(V2nParams p, MarketData data, Regimes regimes, SignalTape tape, StrategyParams params, int fromQ, int toQ) {
        super(p.id(), data, regimes, tape, params, fromQ, toQ);
        this.p = p;
        this.reviewer = new CapitalReviewer(params);
        this.allocator = new CapitalAllocator(params);
        this.assessor = new SystemRiskAssessor(params);
        this.recovery = new RiskRecovery(new MemoryState(), params);
        this.fee = params.feeRate();
    }

    private double impact(Pos x, Series s, int q) {
        return Costs.impact(s.volume(q - 1, MarketData.DAY), x.cost);
    }

    private double net(Pos x, double imp, double price) {
        return V2nRules.netAt(x.qty, x.cost, imp, fee, price);
    }

    private double atrAbs(String sym, int q) {
        MarketData.Indicators ind = data.ind(sym);
        float a = ind.atr24h[Math.min(q / 4, ind.atr24h.length - 1)];
        return a;
    }

    @Override
    protected void manage(int q) {
        for (Pos x : new ArrayList<>(positions.values())) {
            Series s = data.get(x.symbol);
            if (!s.has(q)) {
                if (q > s.lastIndex()) {
                    double last = s.close[s.lastIndex()];
                    sellAll(x, q, last * (1 - 0.005), 0, "DELISTED");
                }
                continue;
            }
            double imp = impact(x, s, q);
            x.path.bar(s.high[q], s.low[q]);
            double o = s.open[q], l = s.low[q], h = s.high[q], c = s.close[q];
            String mode = mode(x);
            boolean realtime = mode.equals("REALTIME");
            // 开盘
            if (realtime && o <= x.stop) {
                sellAll(x, q, o, imp, stopReason(x));
                continue;
            }
            if (p.exitMode().equals("B0") && x.armed && x.floor > 0 && net(x, imp, o) < x.floor && net(x, imp, o) > 0) {
                sellAll(x, q, o, imp, "PROFIT_PROTECTION");
                continue;
            }
            // 开 → 低
            if (realtime && l <= x.stop) {
                sellAll(x, q, x.stop, imp, stopReason(x));
                continue;
            }
            if (p.exitMode().equals("B0") && protectCross(x, imp, o, l, q)) continue;
            x.path.net(net(x, imp, l));
            // 低 → 高
            double netH = net(x, imp, h);
            x.path.net(netH);
            x.peakNet = Math.max(x.peakNet, netH);
            if (p.exitMode().equals("NEW")) {
                if (!x.breakeven && x.peakNet >= p.breakevenR() * x.risk) {
                    x.breakeven = true;
                    x.stop = Math.max(x.stop, V2nRules.priceForNet(x.qty, x.cost, imp, fee, 0));
                }
                if (!x.partial && netH >= p.partialR() * x.risk) {
                    double price = Math.min(h, V2nRules.priceForNet(x.qty, x.cost, imp, fee, p.partialR() * x.risk));
                    sellPart(x, price, imp, p.partialFraction());
                    x.partial = true;
                }
            } else {
                double raw = (h / (x.cost / x.qty) - 1) * 100;
                if (!x.armed && raw >= params.exit().protectArmPct() && netH > 0) x.armed = true;
                if (x.armed) x.floor = floor(x, Math.max(x.peakNet, netH));
            }
            // 高 → 收
            if (realtime && c <= x.stop) {
                sellAll(x, q, x.stop, imp, stopReason(x));
                continue;
            }
            if (p.exitMode().equals("B0") && protectCross(x, imp, h, c, q)) continue;
            if (mode.equals("CONFIRM") && c <= x.stop) {
                sellAll(x, q, c, imp, stopReason(x));
                continue;
            }
            double netC = net(x, imp, c);
            x.path.net(netC);
            x.highestClose = Math.max(x.highestClose, c);
            if (p.exitMode().equals("NEW") && x.partial) {
                double a = atrAbs(x.symbol, q);
                if (a > 0) x.stop = Math.max(x.stop, V2nRules.trailStop(p, x.highestClose, a, x.peakNet / x.risk));
            }
            if (q % 4 == 3 && hourly(x, q, c, imp, netC)) continue;
        }
    }

    /** 整点收盘：1h 收盘止损、L2 时间止损、L3 论点失效。@return 是否卖出 */
    private boolean hourly(Pos x, int q, double c, double imp, double netC) {
        if (mode(x).equals("HOUR") && c <= x.stop) {
            sellAll(x, q, c, imp, stopReason(x));
            return true;
        }
        if (p.timeStopHours() > 0 && (q - x.entryQ) / 4.0 >= p.timeStopHours() && !x.breakeven && netC < 0) {
            sellAll(x, q, c, imp, "L2_TIME");
            return true;
        }
        EarlySignal sig = tape.at(x.symbol, q);
        if (sig == null) return false;
        SymbolBars b = data.bars(x.symbol, q);
        long hour = b.hourly().getLast().openTime();
        if (hour <= x.lastObsHour) return false;
        x.lastObsHour = hour;
        int weak = (sig.rel1h() < 0 ? 1 : 0) + (sig.rel4h() < 0 ? 1 : 0) + (sig.accel() < 0 ? 1 : 0) + (sig.ret15m() < 0 ? 1 : 0);
        x.weakStreak = weak >= params.exit().decayMinWeak() ? x.weakStreak + 1 : 0;
        if (x.weakStreak >= params.exit().decayConsecutive()) {
            switch (p.decayMode()) {
                case "SELL" -> {
                    sellAll(x, q, c, imp, "THESIS_DECAY");
                    return true;
                }
                case "PROFIT_ONLY" -> {
                    if (netC > 0) {
                        sellAll(x, q, c, imp, "THESIS_DECAY");
                        return true;
                    }
                }
                case "RAISE" -> {
                    if (netC > 0) {
                        sellAll(x, q, c, imp, "THESIS_DECAY");
                        return true;
                    }
                    if (!x.breakeven) x.stop = Math.max(x.stop, V2nRules.priceForNet(x.qty, x.cost, imp, fee, -0.5 * x.risk));
                }
                default -> {
                }
            }
        }
        return false;
    }

    private boolean protectCross(Pos x, double imp, double from, double to, int q) {
        if (!x.armed || x.floor <= 0 || to >= from) return false;
        double cross = V2nRules.priceForNet(x.qty, x.cost, imp, fee, x.floor);
        if (cross < from && cross > to) {
            sellAll(x, q, cross, imp, "PROFIT_PROTECTION");
            return true;
        }
        return false;
    }

    private double floor(Pos x, double peak) {
        return ExitPolicy.protectionFloor(BigDecimal.valueOf(x.cost), BigDecimal.valueOf(peak), BigDecimal.valueOf(x.floor),
                params.exit()).doubleValue();
    }

    /** 止损触发方式；不设价格止损（OFF）时，保本和跟踪止损仍按 15m 收盘确认。 */
    private String mode(Pos x) {
        if (!p.stopMode().equals("OFF")) return p.stopMode();
        return x.breakeven || x.partial ? "CONFIRM" : "OFF";
    }

    private String stopReason(Pos x) {
        if (x.partial) return "TRAIL_STOP";
        if (x.breakeven) return "BE_STOP";
        return "L1_STOP";
    }

    private void sellPart(Pos x, double price, double imp, double fraction) {
        double q = x.qty * fraction;
        double c = x.cost * fraction;
        double pnl = q * price * (1 - imp) * (1 - fee) - c;
        x.qty -= q;
        x.cost -= c;
        x.realizedPart += pnl;
        realized += pnl;
    }

    private void sellAll(Pos x, int q, double price, double imp, String reason) {
        double pnl = net(x, imp, price);
        realized += pnl;
        double total = pnl + x.realizedPart;
        positions.remove(x.symbol);
        lastExitQ.put(x.symbol, q);
        boolean stopLike = reason.equals("L1_STOP") || reason.equals("L2_TIME") || (reason.equals("THESIS_DECAY") && total < 0);
        lastExitStop.put(x.symbol, stopLike);
        if (reason.equals("L1_STOP") || reason.equals("L2_TIME")) stopTimes.computeIfAbsent(x.symbol, k -> new ArrayList<>()).add(q);
        if (total < 0) lossLog.add(new double[]{q, total});
        Series s = data.get(x.symbol);
        trades.add(new Trade(x.symbol, x.entryQ, q, x.totalCost, total, reason, regimes.at(x.entryQ), x.risk, x.tranches,
                Math.max(x.path.maxNet, total), Math.min(x.path.minNet, total), x.path.maeBeforePeak(), x.path.mfe(),
                x.atrPctEntry, x.atrDailyEntry,
                reason.equals("L1_STOP") && recovers(s, q, x.path.entryPrice * (1 + 2 * fee)), x.altOpen));
    }

    @Override
    protected void discover(int q) {
        if (q < 7 * MarketData.DAY) return;
        Instant now = now(q);
        String gen = gen(q);
        List<String> universe = data.universe(q);
        SymbolBars btcBars = data.bars(MarketData.BTC, q);
        Ticker24h btcTicker = data.ticker(MarketData.BTC, q);
        MarketData.Context ctx = data.context(q);
        if (btcBars == null || btcTicker == null || ctx == null) return;

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
        signals.values().stream().filter(EarlySignal::early).forEach(s -> {
            logEarly(s.symbol(), q);
            touchEarlySeen(s.symbol(), s.price(), q);
        });

        // 系统级：BTC 1h 急跌或山寨熔断
        RiskRecovery.AltCrash crash = recovery.altCrash(ctx.alt1h(), now);
        double btc1h = MarketData.ret(data.btc, q, 4);
        boolean shock = (!Double.isNaN(btc1h) && btc1h <= params.risk().btcShock1hPct()) || crash.active();
        if (shock && !p.systemMode().equals("NONE")) {
            for (Pos x : new ArrayList<>(positions.values())) {
                Series s = data.get(x.symbol);
                if (!s.has(q)) continue;
                double imp = impact(x, s, q);
                if (p.systemMode().equals("SELL")) sellAll(x, q, s.close[q], imp, "SYSTEM_SELL");
                else if (!x.breakeven) x.stop = Math.max(x.stop, V2nRules.priceForNet(x.qty, x.cost, imp, fee, -p.addMaxLossR() * x.risk));
            }
        }

        // 风控：市场类风险用线上的评估（亏损按笔数冻结改为按钱冻结）
        MarketEvidence ev = new MarketEvidence(5, 0, recovery.confirmedShocks(now), crash.active());
        RiskState raw = assessor.assess(btcBars, btcTicker, tickers, 0, ev);
        recovery.recordShockIfAny(raw, now);
        lossLog.removeIf(e -> e[0] < q - MarketData.DAY);
        double unrealized = unrealized(q);
        double recentLoss = -lossLog.stream().mapToDouble(e -> e[1]).sum() + Math.max(0, -floatingLosses(q));
        if (recentLoss >= p.freezeLossR() * p.riskUnit() && q >= freezeUntil) freezeUntil = q + p.freezeHours() * 4;
        if (q < freezeUntil) {
            List<String> reasons = new ArrayList<>(raw.reasons());
            reasons.add("LOSS_FREEZE_R");
            raw = new RiskState(raw.regime(), false, 0, raw.btc24hPct(), raw.btc4hPct(), raw.btc1hPct(), raw.breadth(), 0, reasons);
        }
        RiskState risk = recovery.apply(raw, gen);
        if (!risk.newRiskAllowed()) return;

        V2nRules.AltState alt = V2nRules.altState(p, ctx.alt24h(), ctx.trendShare());
        if (alt == V2nRules.AltState.WEAK) return;
        double riskMult = alt == V2nRules.AltState.SEMI ? 0.5 : 1.0;

        double deployed = positions.values().stream().mapToDouble(x -> x.cost).sum();
        double available = allocator.available(risk, deployed, realized, unrealized);
        double openRisk = openRisk(q);

        // 候选：新开仓 + 等回落的 + 加仓
        List<EarlySignal> ranked = signals.values().stream()
                .filter(s -> s.early() && s.score() >= params.review().minScore())
                .sorted(Comparator.comparingDouble(EarlySignal::score).reversed()).toList();
        for (EarlySignal s : ranked) {
            String sym = s.symbol();
            Series ser = data.get(sym);
            Ticker24h t = data.ticker(sym, q);
            if (t == null || !ser.has(q)) continue;
            Pos held = positions.get(sym);
            if (held != null) {
                double[] r = tryAdd(held, s, t, q, now, gen, risk, available, openRisk);
                available -= r[0];
                openRisk += r[1];
                continue;
            }
            if (!reentryOk(sym, q)) continue;
            if (!approved(s, t, q, now, gen, risk, lastExitQ.get(sym))) continue;
            double price = ser.close[q];
            MarketData.Indicators ind = data.ind(sym);
            int b = q / 4;
            if (p.trendGate() && !V2nRules.trendOk(price, ind.ema20h[b], ind.ema50h[b], ind.ema20x4h[b])) continue;
            double atr = ind.atr24h[b];
            double ext = V2nRules.extension(price, ind.ema20h[b], atr);
            if (p.extensionGate()) {
                Integer until = pendingUntil.get(sym);
                boolean waiting = until != null && q <= until;
                if (Double.isNaN(ext)) continue;
                if (!waiting && ext > p.extensionMax()) {
                    pendingUntil.put(sym, q + p.extensionWaitHours() * 4);
                    continue;
                }
                if (waiting && ext > p.extensionResume()) continue;
            }
            double atrPct = p.useDailyAtr() ? atrDaily(ser, q) : atr / price * 100;
            double d = V2nRules.stopPct(p, atrPct);
            if (Double.isNaN(d)) continue;
            double size = V2nRules.size(p, d, riskMult);
            double imp = Costs.impact(t.quoteVolume(), size);
            double fill = price * (1 + imp);
            double cost = size * (1 + fee);
            double qty = size / fill;
            double stop = p.stopMode().equals("OFF") ? 0 : fill * (1 - d / 100);
            // 不设价格止损（消融）时按名义 R 计入组合风险，否则一笔就会占满上限
            double stopRisk = stop <= 0 ? p.riskUnit() * riskMult : cost - qty * stop * (1 - imp) * (1 - fee);
            if (cost > available) continue;
            if (openRisk + stopRisk > p.portfolioRiskR() * p.riskUnit()) continue;
            Pos x = new Pos();
            x.symbol = sym;
            x.entryQ = q;
            x.qty = qty;
            x.cost = cost;
            x.totalCost = cost;
            x.firstSize = size;
            x.risk = p.riskUnit() * riskMult;
            x.stopPct = d;
            x.stop = stop;
            x.atrPctEntry = atr / price * 100;
            x.atrDailyEntry = atrDaily(ser, q);
            x.highestClose = price;
            x.lastTranchePrice = fill;
            x.lastAddQ = q;
            x.altOpen = alt == V2nRules.AltState.NORMAL;
            x.path = new Path(q, fill);
            x.lastObsHour = data.bars(sym, q).hourly().getLast().openTime();
            positions.put(sym, x);
            pendingUntil.remove(sym);
            available -= cost;
            openRisk += stopRisk;
        }
    }

    /** 线上同一套买入审核（评分、价差、深度、滑点、RR、追涨、过热、再入场）。 */
    private boolean approved(EarlySignal s, Ticker24h t, int q, Instant now, String gen, RiskState risk, Integer exitQ) {
        String sym = s.symbol();
        Instant lastExit = exitQ == null ? null : now(exitQ);
        int earlySince = exitQ == null ? 0 : earlySince(sym, exitQ, q);
        boolean postExitHit = exitQ == null
                || !EarlySignalCalculator.hitsAfter(data.bars(sym, q), s, Instant.ofEpochMilli(data.time(exitQ) + Series.Q)).isEmpty();
        double[] seen = earlySeen.get(sym);
        ReviewInput in = new ReviewInput(gen, Instant.ofEpochMilli(data.time(q)), now, true, s, t,
                Costs.book(sym, data.get(sym).close[q], t.quoteVolume(), now), risk,
                seen == null ? null : BigDecimal.valueOf(seen[0]), null, lastExit, verified(sym), earlySince, postExitHit);
        ReviewResult r = reviewer.review(in);
        return r.approved();
    }

    /** 被止损后冷却；7 天内止损 2 次停 7 天。 */
    private boolean reentryOk(String sym, int q) {
        Integer exit = lastExitQ.get(sym);
        if (exit != null && Boolean.TRUE.equals(lastExitStop.get(sym)) && q - exit < p.stopCooldownHours() * 4) return false;
        List<Integer> stops = stopTimes.get(sym);
        if (stops != null) {
            long recent = stops.stream().filter(x -> q - x < p.stopBanDays() * MarketData.DAY).count();
            if (recent >= p.stopBanCount()) return false;
        }
        return true;
    }

    /** @return [占用资金, 新增风险] */
    private double[] tryAdd(Pos x, EarlySignal s, Ticker24h t, int q, Instant now, String gen, RiskState risk,
                            double available, double openRisk) {
        if (p.addMode().equals("NONE") || x.adds >= p.maxAdds()) return new double[]{0, 0};
        Series ser = data.get(x.symbol);
        double price = ser.close[q];
        MarketData.Indicators ind = data.ind(x.symbol);
        int b = q / 4;
        double atr = ind.atr24h[b];
        double size = x.firstSize * (p.addMode().equals("WINNERS") ? p.addFraction() : 1.0);
        if (x.totalCost + size > p.maxPositionCost()) return new double[]{0, 0};
        if (q - x.lastAddQ < 4) return new double[]{0, 0};
        if (p.addMode().equals("WINNERS")) {
            if (!x.breakeven) return new double[]{0, 0};
            if (!approved(s, t, q, now, gen, risk, null)) return new double[]{0, 0};
            if (p.trendGate() && !V2nRules.trendOk(price, ind.ema20h[b], ind.ema50h[b], ind.ema20x4h[b])) return new double[]{0, 0};
            double ext = V2nRules.extension(price, ind.ema20h[b], atr);
            if (p.extensionGate() && (Double.isNaN(ext) || ext > p.extensionMax())) return new double[]{0, 0};
        } else {
            // 现行加仓：比上一笔低出足够幅度、信号仍强
            boolean strong = s.early() && s.rel1h() > 0 && s.accel() >= 0 && x.weakStreak == 0;
            double costsPct = 2 * fee * 100;
            double drop = Math.max(params.add().minDropPct(), params.add().atrMultiple() * s.atr1hPct()) + costsPct;
            if (x.tranches >= 2) drop *= params.add().thirdTrancheMultiplier();
            if (!strong || price > x.lastTranchePrice * (1 - drop / 100)) return new double[]{0, 0};
            if (!approved(s, t, q, now, gen, risk, null)) return new double[]{0, 0};
        }
        double imp = Costs.impact(t.quoteVolume(), size);
        double fill = price * (1 + imp);
        double cost = size * (1 + fee);
        if (cost > available) return new double[]{0, 0};
        double qty = size / fill;
        double totQty = x.qty + qty, totCost = x.cost + cost;
        double posImp = Costs.impact(t.quoteVolume(), totCost);
        double newStop;
        if (p.addMode().equals("WINNERS")) {
            double s1 = V2nRules.priceForNet(totQty, totCost, posImp, fee, -p.addMaxLossR() * x.risk);
            double s2 = fill * (1 - x.stopPct / 100);
            newStop = Math.max(s1, s2);
            if (atr > 0 && newStop > price - atr) return new double[]{0, 0};
        } else {
            newStop = p.stopMode().equals("OFF") ? 0 : (totCost / totQty) * (1 - x.stopPct / 100);
        }
        double before = x.breakeven ? 0 : x.stop <= 0 ? x.risk : Math.max(0, x.cost - x.qty * x.stop * (1 - posImp) * (1 - fee));
        double after = newStop <= 0 ? x.risk : Math.max(0, totCost - totQty * newStop * (1 - posImp) * (1 - fee));
        if (openRisk - before + after > p.portfolioRiskR() * p.riskUnit()) return new double[]{0, 0};
        x.qty = totQty;
        x.cost = totCost;
        x.totalCost += cost;
        x.stop = newStop;
        x.tranches++;
        x.adds++;
        x.lastAddQ = q;
        x.lastTranchePrice = fill;
        x.breakeven = false;
        x.peakNet = V2nRules.netAt(totQty, totCost, posImp, fee, price);
        x.armed = false;
        x.floor = 0;
        return new double[]{cost, after - before};
    }

    /** 还没移到保本的持仓，打到止损时的亏损合计。 */
    private double openRisk(int q) {
        double sum = 0;
        for (Pos x : positions.values()) {
            if (x.breakeven) continue;
            if (x.stop <= 0) {
                sum += x.risk;
                continue;
            }
            Series s = data.get(x.symbol);
            double imp = impact(x, s, Math.max(1, q));
            sum += Math.max(0, x.cost - x.qty * x.stop * (1 - imp) * (1 - fee));
        }
        return sum;
    }

    private double floatingLosses(int q) {
        double sum = 0;
        for (Pos x : positions.values()) {
            Series s = data.get(x.symbol);
            double c = s.lastClose(q, 400);
            if (Double.isNaN(c)) continue;
            double n = net(x, impact(x, s, Math.max(1, q)), c);
            if (n < 0) sum += n;
        }
        return sum;
    }

    private void touchEarlySeen(String sym, double price, int q) {
        double[] row = earlySeen.get(sym);
        Integer exit = lastExitQ.get(sym);
        boolean reset = row == null || (q - row[2]) >= 7 * MarketData.DAY || (exit != null && exit > row[1]);
        if (reset) earlySeen.put(sym, new double[]{price, q, q});
        else row[2] = q;
    }

    @Override
    protected double deployedCost() {
        return positions.values().stream().mapToDouble(x -> x.cost).sum();
    }

    @Override
    protected double unrealized(int q) {
        double sum = 0;
        for (Pos x : positions.values()) {
            Series s = data.get(x.symbol);
            double c = s.lastClose(q, 400);
            if (Double.isNaN(c)) continue;
            sum += net(x, impact(x, s, Math.max(1, q)), c);
        }
        return sum;
    }

    @Override
    protected void closeAll(int q) {
        for (Pos x : new ArrayList<>(positions.values())) {
            Series s = data.get(x.symbol);
            sellAll(x, q, s.lastClose(q, 100_000), impact(x, s, q), "END");
        }
    }
}
