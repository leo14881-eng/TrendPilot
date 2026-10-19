package com.trendpilot.btcdaily;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.trendpilot.market.Kline;

/**
 * BTC 每日短线规则（纯计算，实盘影子和历史回测共用同一套代码）。
 * <ul>
 * <li>计划：空仓时按今日开盘价向下挂三档；持仓跨天时只把没用完的档位从上次买入价继续往下挂。</li>
 * <li>成交：用已收盘 K 线的最低/最高价判断；同一根 K 线里先算已有持仓的止盈，止盈了就不再买（保守）。</li>
 * <li>不止损：亏损时不卖，跨天后把止盈幅度降到 carryTpPct；TRAIL 模式下到了止盈价不立即卖，跟踪最高价，
 *     回落才卖、最低按原止盈价。每天最多完成一轮。</li>
 * <li>TRAIL_B（方案 B）：均价 +3% 启动跟踪，从最高价回落 3×ATR 全卖；满 30 天未卖则含费成本 +0.3% 以上卖；
 *     与研究引擎 BtcPlanStudy 的 TRAIL 卖法同一判断。</li>
 * <li>硬规则（用户 2026-10-20）：BTC 绝不卖亏。任何卖出在成交前都检查到手金额 &gt; 对应成本，否则不卖。</li>
 * </ul>
 */
public final class BtcDailyRules {

    private BtcDailyRules() {
    }

    public static LocalDate dayOf(long epochMs) {
        return java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate();
    }

    public static long dayStart(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** 计划需要的最少日线根数。 */
    public static int dailyNeeded(BtcDailyProperties p) {
        return Math.max(p.atrDays() + 1, Math.max(p.emaDays(), p.filters().emaLong()) + p.filters().slopeDays());
    }

    /** 不带市场 / 宏观过滤的计划（回测基准）。 */
    public static DailyPlan plan(LocalDate day, List<Kline> dailyBefore, double open, Book book, BtcDailyProperties p) {
        return plan(day, dailyBefore, open, book, p, null);
    }

    /**
     * @param dailyBefore 计划日之前已收盘的日线（升序），至少 {@link #dailyNeeded} 根
     * @param open        计划日日线开盘价
     * @param ctx         市场与宏观数据；null 表示不做过滤（回测基准）
     */
    public static DailyPlan plan(LocalDate day, List<Kline> dailyBefore, double open, Book book, BtcDailyProperties p,
                                 MarketContext ctx) {
        int need = dailyNeeded(p);
        if (dailyBefore.size() < need) {
            throw new IllegalArgumentException("日线不足：需要 " + need + " 根，只有 " + dailyBefore.size());
        }
        MarketContext.Gate gate = MarketContext.Gate.NONE;
        if (ctx != null && p.filters().enabled()) {
            gate = ctx.gate(downtrend(dailyBefore, p), p.filters());
        }
        double atr = atr(dailyBefore, p.atrDays());
        double ema = ema(dailyBefore, p.emaDays());
        double prevClose = dailyBefore.getLast().close();
        boolean strong = prevClose > ema;
        double tpPct = Math.max(p.tpAtrMult() * atr / open * 100, p.tpMinPct());

        List<DailyPlan.Level> levels = new ArrayList<>();
        List<Double> tranches = p.tranches();
        if (!book.holding()) {
            double first = p.firstOpenPct() > 0 ? open * (1 - p.firstOpenPct() / 100)
                    : open - (strong ? p.kStrong() : p.kWeak()) * atr;
            double spent = 0;
            for (int i = 0; i < tranches.size() && !gate.skipNew(); i++) {
                double usdt = round2(tranches.get(i) * gate.multiplier());
                if (spent + usdt > p.capUsdt() + 1e-6) break;
                spent += usdt;
                levels.add(new DailyPlan.Level(i, round2(first - i * p.stepAtr() * atr), usdt));
            }
        } else if (book.trailPeak <= 0 || p.exit().trailB()) { // 正在跟踪止盈的仓位不再加仓（方案 B 照常加仓）
            double spent = book.cost;
            for (int i = book.buys, j = 1; i < tranches.size(); i++, j++) {
                if (spent + tranches.get(i) > p.capUsdt() + 1e-6) break;
                spent += tranches.get(i);
                levels.add(new DailyPlan.Level(i, round2(book.lastBuyPrice - j * p.stepAtr() * atr), tranches.get(i)));
            }
        }
        List<String> notes = new ArrayList<>(gate.reasons());
        if (ctx != null && !ctx.gaps().isEmpty()) notes.add("数据缺失：" + String.join("、", ctx.gaps()));
        return new DailyPlan(day, p.version(), open, atr, ema, prevClose, strong, book.holding(), tpPct,
                List.copyOf(levels), gate.skipNew(), gate.multiplier(), String.join("；", notes), "V2",
                regime(dailyBefore, p), 0, 0);
    }

    /**
     * 实盘缺数据保护（见 {@link MarketContext#missingDataGate}）：只作用于新一轮的挂单，已有仓位的止盈和加仓不变。
     * 不开新一轮时去掉全部挂单；减半时按比例缩小每档金额（总乘数不低于 minMultiplier）。
     */
    static DailyPlan guardMissingData(DailyPlan plan, MarketContext.Gate guard, double minMultiplier) {
        if (plan.carried() || plan.levels().isEmpty() || guard.reasons().isEmpty()) return plan;
        String reasons = plan.reasons().isBlank() ? guard.text() : plan.reasons() + "；" + guard.text();
        if (guard.skipNew()) {
            return new DailyPlan(plan.day(), plan.version(), plan.open(), plan.atr(), plan.ema(), plan.prevClose(),
                    plan.strong(), plan.carried(), plan.tpPct(), List.of(), true, 0, reasons, plan.strategy(),
                    plan.regime(), plan.hourAtr(), plan.addFloor());
        }
        double before = plan.multiplier() > 0 ? plan.multiplier() : 1;
        double after = Math.max(minMultiplier, before * guard.multiplier());
        double factor = Math.min(1, after / before);
        List<DailyPlan.Level> levels = plan.levels().stream()
                .map(l -> new DailyPlan.Level(l.index(), l.price(), round2(l.usdt() * factor))).toList();
        return new DailyPlan(plan.day(), plan.version(), plan.open(), plan.atr(), plan.ema(), plan.prevClose(),
                plan.strong(), plan.carried(), plan.tpPct(), levels, plan.skipNew(), before * factor, reasons,
                plan.strategy(), plan.regime(), plan.hourAtr(), plan.addFloor());
    }

    /** 行情：UP 昨收 > EMA20 > EMA50；DOWN 见 {@link #downtrend}；其余 SIDE。 */
    static String regime(List<Kline> daily, BtcDailyProperties p) {
        if (downtrend(daily, p)) return "DOWN";
        double ema = ema(daily, p.emaDays());
        double emaLong = ema(daily, p.filters().emaLong());
        return daily.getLast().close() > ema && ema > emaLong ? "UP" : "SIDE";
    }

    /** V3 需要的计划日之前已收盘 1h K 线根数（下限）。 */
    public static final int HOURLY_NEEDED = 48;

    /**
     * V3 计划：先判断行情，上升在支撑位挂三档满额，震荡挂更深的三档减半，下跌不挂；跨天仓位不加仓。
     *
     * @param hourlyBefore 计划日之前已收盘的 1h K 线（升序），至少 {@link #HOURLY_NEEDED} 根
     */
    public static DailyPlan planV3(LocalDate day, List<Kline> dailyBefore, List<Kline> hourlyBefore, double open,
                                   Book book, BtcDailyProperties p, MarketContext ctx) {
        int need = dailyNeeded(p);
        if (dailyBefore.size() < need) {
            throw new IllegalArgumentException("日线不足：需要 " + need + " 根，只有 " + dailyBefore.size());
        }
        if (hourlyBefore.size() < HOURLY_NEEDED) {
            throw new IllegalArgumentException("1h K 线不足：需要 " + HOURLY_NEEDED + " 根，只有 " + hourlyBefore.size());
        }
        BtcDailyProperties.V3 v = p.v3();
        String regime = regime(dailyBefore, p);
        MarketContext.Gate gate = MarketContext.Gate.NONE;
        if (ctx != null && p.filters().enabled()) gate = ctx.gate(false, p.filters());
        double atr = atr(dailyBefore, p.atrDays());
        double ema = ema(dailyBefore, p.emaDays());
        double emaLong = ema(dailyBefore, p.filters().emaLong());
        double prevClose = dailyBefore.getLast().close();
        double hourAtr = atr(hourlyBefore, v.hourAtrBars());
        boolean skip = gate.skipNew() || "DOWN".equals(regime);
        double mult = gate.multiplier() * ("SIDE".equals(regime) ? v.sideMultiplier() : 1);
        mult = Math.max(mult, p.filters().minMultiplier());

        List<DailyPlan.Level> levels = new ArrayList<>();
        if (!book.holding() && !skip) {
            List<Double> prices = "UP".equals(regime)
                    ? supportLevels(dailyBefore, hourlyBefore, open, atr, p)
                    : List.of(open - v.sideAtr() * atr, open - (v.sideAtr() + p.stepAtr()) * atr,
                    open - (v.sideAtr() + 2 * p.stepAtr()) * atr);
            double spent = 0;
            for (int i = 0; i < p.tranches().size(); i++) {
                double usdt = round2(p.tranches().get(i) * mult);
                if (spent + usdt > p.capUsdt() + 1e-6) break;
                spent += usdt;
                levels.add(new DailyPlan.Level(i, round2(prices.get(i)), usdt));
            }
        }
        List<String> notes = new ArrayList<>();
        notes.add("行情：" + DailyPlan.regimeName(regime) + ("DOWN".equals(regime) ? "，不挂单" : "SIDE".equals(regime)
                ? "，只挂深位、金额 ×" + fmt(v.sideMultiplier()) : ""));
        notes.addAll(gate.reasons());
        if (ctx != null && !ctx.gaps().isEmpty()) notes.add("数据缺失：" + String.join("、", ctx.gaps()));
        double addFloor = "UP".equals(regime) ? ema : emaLong;
        return new DailyPlan(day, p.version(), open, atr, ema, prevClose, prevClose > ema, book.holding(),
                v.partialAtPct(), List.copyOf(levels), skip, skip ? 0 : mult, String.join("；", notes), "V3", regime,
                hourAtr, round2(addFloor));
    }

    /** 上升行情的三档：1h EMA20、前一日 VWAP、前一日低点里低于开盘的，从高到低；不够的往下补，相邻至少 minGapAtr × ATR。 */
    static List<Double> supportLevels(List<Kline> dailyBefore, List<Kline> hourlyBefore, double open, double atr,
                                      BtcDailyProperties p) {
        BtcDailyProperties.V3 v = p.v3();
        long dayStart = dailyBefore.getLast().closeTime() + 1;
        double num = 0, den = 0;
        for (Kline h : hourlyBefore) {
            if (h.openTime() < dayStart - 86_400_000L) continue;
            double tp = (h.high() + h.low() + h.close()) / 3;
            // 只有成交额（USDT）：VWAP = Σ成交额 / Σ(成交额 / 典型价)
            num += h.quoteVolume();
            den += h.quoteVolume() / tp;
        }
        List<Double> cands = new ArrayList<>();
        cands.add(ema(hourlyBefore, v.hourEmaBars()));
        if (den > 0) cands.add(num / den);
        cands.add(dailyBefore.getLast().low());
        List<Double> below = cands.stream().filter(c -> c < open * 0.999).sorted(java.util.Comparator.reverseOrder()).toList();
        List<Double> out = new ArrayList<>();
        double prev = Double.NaN;
        for (int i = 0; i < p.tranches().size(); i++) {
            double c;
            if (i == 0) {
                c = below.isEmpty() ? open - v.firstFallbackAtr() * atr : below.getFirst();
            } else {
                c = i < below.size() ? below.get(i) : prev - p.stepAtr() * atr;
                c = Math.min(c, prev - v.minGapAtr() * atr);
            }
            out.add(c);
            prev = c;
        }
        return out;
    }

    /** 下跌趋势：昨收 < EMA20 且 EMA20 < 长期 EMA 且 EMA20 比 slopeDays 天前低。 */
    static boolean downtrend(List<Kline> daily, BtcDailyProperties p) {
        int slope = p.filters().slopeDays();
        double ema = ema(daily, p.emaDays());
        double emaBefore = ema(daily.subList(0, daily.size() - slope), p.emaDays());
        double emaLong = ema(daily, p.filters().emaLong());
        return daily.getLast().close() < ema && ema < emaLong && ema < emaBefore;
    }

    /** 当前持仓的止盈价（含手续费的均价 × (1 + 幅度)）。 */
    public static double takeProfitPrice(DailyPlan plan, Book book, BtcDailyProperties p) {
        return book.avgCost() * (1 + takeProfitPct(plan, book, p) / 100);
    }

    public static double takeProfitPct(DailyPlan plan, Book book, BtcDailyProperties p) {
        if (!plan.v3() && p.exit().trailB()) return p.exit().activatePct();
        boolean carried = book.openedDay != null && book.openedDay.isBefore(plan.day());
        return carried ? p.carryTpPct() : plan.tpPct();
    }

    /** 处理一根已收盘 K 线，返回成交（可能为空），并更新账本。 */
    public static List<Fill> onBar(DailyPlan plan, Book book, Kline bar, BtcDailyProperties p) {
        List<Fill> fills = new ArrayList<>();
        double slip = p.slippageBps() / 10_000;
        LocalDate day = plan.day();

        book.lastRound = null;
        if (book.holding()) {
            track(book, bar, p);
            Exit e = exitSignal(plan, book, bar, p);
            // 绝不卖亏（只有回测对比用的止损类卖法例外，实盘的 live() 不会用到它们）
            if (e != null && !lossCompare(plan, p) && !profitable(book, e, slip, p)) e = null;
            if (e != null && e.fraction() < 1) {
                double price = e.price() * (1 - slip);
                double q = book.qty * e.fraction();
                double c = book.cost * e.fraction();
                double gross = q * price;
                double fee = gross * p.feeRate();
                double net = gross - fee;
                double pnl = net - c;
                book.qty -= q;
                book.cost -= c;
                book.soldCost += c;
                book.soldProceeds += net;
                book.realized += pnl;
                fills.add(new Fill(day, bar.openTime(), Fill.Side.SELL, book.buys, price, q, net, fee, pnl,
                        e.reason(), book.qty, book.cost));
                return fills;
            }
            if (e != null) {
                double price = e.price() * (1 - slip);
                double gross = book.qty * price;
                double fee = gross * p.feeRate();
                double net = gross - fee;
                double pnl = net - book.cost;
                fills.add(new Fill(day, bar.openTime(), Fill.Side.SELL, book.buys, price, book.qty, net, fee, pnl,
                        e.reason(), 0, 0));
                book.realized += pnl;
                book.rounds++;
                double roundCost = book.cost + book.soldCost;
                double roundNet = net + book.soldProceeds;
                double roundPnl = roundNet - roundCost;
                if (roundPnl > 0) book.wins++;
                book.lastRound = new Book.Round(p.version(), book.openedDay, book.openedAt, bar.closeTime(),
                        (bar.closeTime() + 1 - book.openedAt) / 60_000, book.buys, roundCost, roundNet, roundPnl,
                        roundPnl / roundCost * 100, book.maePct, book.mfePct, e.reason(), book.openFilters,
                        new java.util.LinkedHashMap<>(book.stopHits), book.openRegime);
                book.openRegime = "";
                book.soldCost = 0;
                book.soldProceeds = 0;
                book.qty = 0;
                book.openedAt = 0;
                book.maePct = 0;
                book.mfePct = 0;
                book.openFilters = "";
                book.stopHits.clear();
                book.cost = 0;
                book.buys = 0;
                book.lastBuyPrice = 0;
                book.trailPeak = 0;
                book.openedDay = null;
                book.roundMaxFloatLoss = 0;
                book.lastExitDay = day;
                return fills;
            }
        }
        boolean newRoundBlocked = !book.holding() && day.equals(book.lastExitDay);
        if (!newRoundBlocked && (book.trailPeak <= 0 || p.exit().trailB())) {
            for (DailyPlan.Level l : plan.levels()) {
                if (l.index() != book.buys) continue;
                double trigger = l.price();
                if (book.holding() && plan.v3() && trigger < plan.addFloor()) break; // 趋势不在了：不再加仓
                if (book.holding()) {
                    // 加仓至少比上一笔实际成交低 stepAtr × ATR：开盘已跳到档位下方时，上一笔会按开盘价成交，
                    // 若仍用计划价，下一档可能只差几百美元就连续加仓
                    trigger = Math.min(trigger, round2(book.lastBuyPrice - p.stepAtr() * plan.atr()));
                }
                if (bar.low() > trigger) continue;
                if (book.cost + l.usdt() > p.capUsdt() + 1e-6) break;
                double price = Math.min(trigger, bar.open()) * (1 + slip);
                double fee = l.usdt() * p.feeRate() / (1 + p.feeRate());
                double qty = (l.usdt() - fee) / price;
                if (!book.holding()) {
                    book.openedDay = day;
                    book.openedAt = bar.openTime();
                    if (p.exit().trailB() && !plan.v3()) book.trailPeak = price; // 方案 B：从买入价开始记最高价
                    book.openFilters = plan.reasons();
                    book.openRegime = plan.regime();
                }
                book.qty += qty;
                book.cost += l.usdt();
                book.buys++;
                book.lastBuyPrice = price;
                String reason = l.index() == 0 ? "第 1 档买入" : "第 " + (l.index() + 1) + " 档加仓";
                if (bar.open() < trigger) reason += "（开盘已低于档位，按开盘价成交）";
                fills.add(new Fill(day, bar.openTime(), Fill.Side.BUY, l.index(), price, qty, l.usdt(), fee, 0,
                        reason, book.qty, book.cost));
            }
        }
        if (book.holding()) {
            if (!fills.isEmpty()) track(book, bar, p);
            double floating = book.unrealized(bar.low(), p.feeRate());
            book.roundMaxFloatLoss = Math.min(book.roundMaxFloatLoss, floating);
            book.maxFloatLoss = Math.min(book.maxFloatLoss, floating);
        }
        return fills;
    }

    record Exit(double price, String reason, double fraction) {
        Exit(double price, String reason) {
            this(price, reason, 1);
        }
    }

    /** 硬规则：卖出（扣滑点和手续费后）到手金额必须大于对应成本，否则不卖。 */
    static boolean profitable(Book book, Exit e, double slip, BtcDailyProperties p) {
        double q = book.qty * e.fraction();
        double c = book.cost * e.fraction();
        return q > 0 && q * e.price() * (1 - slip) * (1 - p.feeRate()) > c;
    }

    /** 回测里"如果止损会怎样"的对比卖法（STOP、V3 被套保护）：允许亏损卖出，实盘不使用。 */
    static boolean lossCompare(DailyPlan plan, BtcDailyProperties p) {
        return plan.v3() ? !"NONE".equals(p.v3().guard()) : "STOP".equals(p.exit().mode());
    }

    /** 方案 B 的最低卖出挂价（未扣滑点）：成交价 ≥ 含费均价 × (1 + 手续费 + floorPct%)。 */
    public static double floorB(Book book, BtcDailyProperties p) {
        return book.avgCost() * (1 + p.feeRate() + p.exit().floorPct() / 100) / (1 - p.slippageBps() / 10_000);
    }

    /** 方案 B 的跟踪启动价。 */
    public static double activateB(Book book, BtcDailyProperties p) {
        return book.avgCost() * (1 + p.exit().activatePct() / 100);
    }

    /** 方案 B 当前的跟踪卖出线（未启动返回 NaN）。 */
    public static double trailLineB(Book book, DailyPlan plan, BtcDailyProperties p) {
        if (book.trailPeak < activateB(book, p)) return Double.NaN;
        return Math.max(floorB(book, p), book.trailPeak - p.exit().giveAtr() * plan.atr());
    }

    /** 方案 B：是否已持有满 timeoutDays 天（timeoutDays &lt; 0 = 永不超时）。 */
    public static boolean timeoutB(Book book, long barOpen, BtcDailyProperties p) {
        int d = p.exit().timeoutDays();
        return d > 0 && book.openedAt > 0 && barOpen - book.openedAt >= d * 86_400_000L;
    }

    /** 方案 B 超时后的卖出目标（未扣滑点）：含费均价 × (1 + 手续费 + timeoutTargetPct%)，不低于保底价。 */
    public static double timeoutTargetB(Book book, BtcDailyProperties p) {
        double t = book.avgCost() * (1 + p.feeRate() + p.exit().timeoutTargetPct() / 100) / (1 - p.slippageBps() / 10_000);
        return Math.max(t, floorB(book, p));
    }

    /** 按卖出方式判断这根 K 线是否全部卖出（价格未扣滑点）。 */
    static Exit exitSignal(DailyPlan plan, Book book, Kline bar, BtcDailyProperties p) {
        BtcDailyProperties.Exit x = p.exit();
        boolean carried = book.openedDay != null && book.openedDay.isBefore(plan.day());
        if (plan.v3()) return exitV3(plan, book, bar, p, carried);
        double target = takeProfitPrice(plan, book, p);
        switch (x.mode()) {
            case "TRAIL_B" -> {
                // 与 BtcPlanStudy 的 TRAIL 卖法一致：最高价（从买入价起）达到激活价后，回落到 max(最高价 − giveAtr×ATR, 保底) 卖；
                // 满 timeoutDays 天未卖，碰到保底价就卖；本根的新高只影响下一根的判断（保守）
                double floor = floorB(book, p);
                Exit out = null;
                double line = trailLineB(book, plan, p);
                if (!Double.isNaN(line) && bar.low() <= line) {
                    double price = Math.min(line, bar.open());
                    if (price >= floor) out = new Exit(price, carried ? "跟踪止盈（跨天）" : "跟踪止盈");
                }
                double tt = timeoutTargetB(book, p);
                if (out == null && timeoutB(book, bar.openTime(), p) && bar.high() >= tt) {
                    out = new Exit(Math.max(tt, bar.open()), "持有满 " + x.timeoutDays() + " 天，成本以上卖出");
                }
                book.trailPeak = Math.max(book.trailPeak, bar.high());
                return out;
            }
            case "STOP" -> {
                double stop = book.avgCost() * (1 - x.hardStopPct() / 100);
                if (bar.low() <= stop) return new Exit(Math.min(stop, bar.open()), "止损 " + fmt(x.hardStopPct()) + "%");
            }
            case "PARTIAL" -> {
                if (book.trailPeak > 0) {
                    Exit t = trailExit(book, bar, target, book.trailPeak - x.partialTrailAtr() * plan.atr(), "剩余仓位跟踪止盈");
                    if (t == null) book.trailPeak = Math.max(book.trailPeak, bar.high());
                    return t;
                }
                if (bar.high() >= target) {
                    book.trailPeak = bar.high();
                    return new Exit(Math.max(target, bar.open()), "到止盈价先卖 " + fmt(x.partialPct()) + "%",
                            x.partialPct() / 100);
                }
                return null;
            }
            case "TRAIL" -> {
                if (book.trailPeak > 0) {
                    // 保底 = 原止盈价（加仓后按新均价重算），所以不会比 FIXED 卖得低，也不会卖亏
                    double stop = Math.max(book.trailPeak - x.trailAtr() * plan.atr(), target);
                    if (bar.low() <= stop) {
                        double price = Math.min(stop, bar.open());
                        if (price < target) {
                            if (bar.high() < target) return null; // 跳空到保底价下方：不卖亏，等回到保底价
                            price = target;
                        }
                        return new Exit(price, carried ? "回本后跟踪止盈" : "移动止盈");
                    }
                    book.trailPeak = Math.max(book.trailPeak, bar.high());
                } else if (bar.high() >= target) {
                    book.trailPeak = bar.high(); // 到了原止盈价：启动跟踪，下一根 K 线起判断回落
                }
                return null;
            }
            default -> {
            }
        }
        if (bar.high() >= target) return new Exit(Math.max(target, bar.open()), carried ? "跨天回本止盈" : "当日止盈");
        return null;
    }

    /** V3：涨 partialAtPct% 先卖一部分并启动跟踪；跨天仓位回到保底价也启动跟踪；跟踪回落到保底价以上卖出。 */
    static Exit exitV3(DailyPlan plan, Book book, Kline bar, BtcDailyProperties p, boolean carried) {
        BtcDailyProperties.V3 v = p.v3();
        double avg = book.avgCost();
        double floor = avg * (1 + v.floorPct() / 100);
        double first = avg * (1 + v.partialAtPct() / 100);
        double hourAtr = plan.hourAtr() > 0 ? plan.hourAtr() : plan.atr() / 5; // 旧计划没有 1h ATR 时的近似
        switch (v.guard()) {
            case "TREND" -> {
                if ("DOWN".equals(plan.regime())) return new Exit(bar.open(), "趋势转跌离场");
            }
            case "TIME" -> {
                if (book.openedDay != null
                        && java.time.temporal.ChronoUnit.DAYS.between(book.openedDay, plan.day()) >= v.guardDays()) {
                    return new Exit(bar.open(), "持有满 " + v.guardDays() + " 天离场");
                }
            }
            case "STOP" -> {
                double stop = avg * (1 - v.guardStopPct() / 100);
                if (bar.low() <= stop) return new Exit(Math.min(stop, bar.open()), "止损 " + fmt(v.guardStopPct()) + "%");
            }
            default -> {
            }
        }
        if (book.trailPeak > 0) {
            Exit t = trailExit(book, bar, floor, book.trailPeak - v.trailHourAtr() * hourAtr, "跟踪止盈");
            if (t != null) return t;
        }
        if (book.soldCost <= 0 && bar.high() >= first) {
            book.trailPeak = Math.max(book.trailPeak, bar.high());
            return new Exit(Math.max(first, bar.open()), "涨 " + fmt(v.partialAtPct()) + "% 先卖 "
                    + (Math.abs(v.partialPct() - 33.33) < 0.01 ? "1/3" : fmt(v.partialPct()) + "%"), v.partialPct() / 100);
        }
        if (book.trailPeak > 0) {
            book.trailPeak = Math.max(book.trailPeak, bar.high());
        } else if (carried && bar.high() >= floor) {
            book.trailPeak = bar.high(); // 被套后回本：启动跟踪，不是 +0.3% 就走
        }
        return null;
    }

    /** 跟踪卖出：回落到 max(trail, 保底价) 卖；跳空到保底价下方且没回到保底价时不卖亏。 */
    private static Exit trailExit(Book book, Kline bar, double floor, double trail, String reason) {
        double stop = Math.max(trail, floor);
        if (bar.low() > stop) return null;
        double price = Math.min(stop, bar.open());
        if (price < floor) {
            if (bar.high() < floor) return null;
            price = floor;
        }
        return new Exit(price, reason);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** 持仓期间：相对当时均价的最大不利 / 有利幅度，以及各候选止损是否首次触发（假设止损，不实际卖出）。 */
    static void track(Book book, Kline bar, BtcDailyProperties p) {
        double avg = book.avgCost();
        book.maePct = Math.min(book.maePct, (bar.low() / avg - 1) * 100);
        book.mfePct = Math.max(book.mfePct, (bar.high() / avg - 1) * 100);
        for (double c : p.stopCandidatesPct()) {
            String key = String.valueOf(c);
            if (book.stopHits.containsKey(key)) continue;
            double stop = avg * (1 - c / 100);
            if (bar.low() <= stop) {
                double price = Math.min(stop, bar.open()) * (1 - p.slippageBps() / 10_000);
                double pnl = book.qty * price * (1 - p.feeRate()) - book.cost;
                book.stopHits.put(key, new Book.StopHit(bar.openTime(), round2(price), round2(pnl)));
            }
        }
    }

    /** 简单平均真实波幅（最近 n 根）。 */
    static double atr(List<Kline> daily, int n) {
        double sum = 0;
        for (int i = daily.size() - n; i < daily.size(); i++) {
            Kline k = daily.get(i);
            double prev = daily.get(i - 1).close();
            sum += Math.max(k.high() - k.low(), Math.max(Math.abs(k.high() - prev), Math.abs(k.low() - prev)));
        }
        return sum / n;
    }

    /** 收盘价 EMA（用全部给定日线递推）。 */
    static double ema(List<Kline> daily, int n) {
        double a = 2.0 / (n + 1);
        double e = daily.getFirst().close();
        for (int i = 1; i < daily.size(); i++) {
            e = a * daily.get(i).close() + (1 - a) * e;
        }
        return e;
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
