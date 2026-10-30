package com.trendpilot.lpshadow;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个池子的全部虚拟仓位和基准，逐分钟吃快照（纯计算，不读写数据库；持久化通过 {@link Ledger}）。规则见 docs/规则说明.md "LP 影子盘"。
 *
 * <p><b>有效区间</b>：上一条和这一条快照都是 OK 且正好相隔 60 秒；对冲策略还要求这一条有永续报价。
 * 只有有效区间才计手续费、才会调仓和调整对冲；缺数据的那一段不计费、不动作，只记一条池子级别的 GAP 事件。
 *
 * <p><b>手续费</b>：开始和结束时 tick 都在 [tickLower, tickUpper) 内时
 * fee0 = L × Δfg0 / 2^128 × L_active / (L_active + L)，fee1 同理（Δfg 按 mod 2^256 求差，L_active 取区间开始时的值）；
 * 只有一端在区间内按一半计；都不在不计。按结束时的价格换算成 USDC 单独累计，不复投。
 *
 * <p><b>调仓</b>：价格出区间（冷却 N 分钟后仍在区间外）时以当前价格为中心重开同宽度区间；
 * 成本 = L2 执行费（实时 gasPrice × 60 万 gas）+ L1 数据费（链上只读估算，见 {@link L1Fees}），按 ETH 价格换成 USDC，
 * 再加一半仓位价值 × (池子费率 + 0.02% 滑点)，从做市资金里扣。gasPrice / L1 费这一分钟缺失时用最近一次有效值；
 * 超过 1 小时没有有效值，这一分钟不调仓（记一次 COST_GAP）。
 *
 * <p><b>对冲</b>：ETHUSDT 永续空单，目标数量 = 仓位里的 ETH 数量；偏离超过仓位价值 2% 才调整，taker 0.05%；
 * 保证金按 3 倍杠杆从本金里划出（不参与做市）；每 8 小时（UTC 00/08/16）按结算前最后看到的资金费率结算（费率为正时空单收钱）；
 * 离强平价不到 15% 记一次风险事件；价格到达强平价按爆仓处理（保证金清零、空单平掉，之后不再对冲）。
 */
final class LpBook {

    static final long DEFAULT_REBALANCE_GAS = 600_000;   // decreaseLiquidity + collect + swap + mint
    static final long COST_MAX_AGE = 3600;
    static final double SLIPPAGE = 0.0002;
    static final double HEDGE_BAND = 0.02;
    static final double TAKER = 0.0005;
    static final double LEVERAGE = 3;
    static final double MAINTENANCE = 0.005;
    static final double RISK_DISTANCE = 0.15;
    static final long FUNDING_PERIOD = 8 * 3600;

    /** 持久化接口：开 / 平仓位记录、事件。 */
    interface Ledger {
        long openPosition(String strategy, String pool, int tickLower, int tickUpper, double liquidity, long ts);

        void closePosition(long id, long ts);

        void event(long ts, String strategy, String type, Map<String, Object> detail);
    }

    final PoolInfo pool;
    final List<Strategy> strategies;
    final Map<String, PositionState> states = new LinkedHashMap<>();
    private final Ledger ledger;
    private final double rebalanceGas;
    Snapshot prev;
    /** 最近一次有效的 gasPrice / L1 费（缺失时最多沿用 1 小时）。 */
    BigInteger lastGasWei;
    long lastGasTs;
    BigInteger lastL1Wei;
    long lastL1Ts;
    boolean costGap;
    long gapStart;
    /** 最近一次看到的永续报价（任何快照，包括池子数据缺失的 GAP 行），用于资金费结算。 */
    Double lastRate;
    long lastRateTs;
    Double lastMark;

    LpBook(PoolInfo pool, List<Strategy> strategies, Map<String, PositionState> restored, Snapshot prev, Ledger ledger) {
        this(pool, strategies, restored, prev, ledger, DEFAULT_REBALANCE_GAS);
    }

    LpBook(PoolInfo pool, List<Strategy> strategies, Map<String, PositionState> restored, Snapshot prev, Ledger ledger,
           long rebalanceGas) {
        this.pool = pool;
        this.strategies = strategies;
        this.ledger = ledger;
        this.rebalanceGas = rebalanceGas;
        this.prev = prev;
        if (prev != null) {
            notePerp(prev);
            noteCosts(prev);
        }
        for (Strategy s : strategies) {
            PositionState st = restored.get(s.name());
            if (st == null) {
                st = new PositionState();
                st.strategy = s.name();
                st.pool = s.pool();
            }
            states.put(s.name(), st);
        }
    }

    boolean started() {
        return states.values().stream().anyMatch(s -> s.started);
    }

    /** 处理一分钟的快照（同一分钟或更早的重复快照忽略）。 */
    void onSnapshot(Snapshot cur) {
        if (prev != null && cur.ts() <= prev.ts()) return;
        if (started()) settleFunding(cur);
        notePerp(cur);
        noteCosts(cur);
        boolean missingMinutes = prev != null && cur.ts() - prev.ts() > 60;
        if (!cur.ok()) {
            if (gapStart == 0) gapStart = missingMinutes ? prev.ts() + 60 : cur.ts();
            prev = cur;
            return;
        }
        if (gapStart != 0 || missingMinutes) {
            long from = gapStart != 0 ? gapStart : prev.ts() + 60;
            ledger.event(cur.ts(), pool.key(), "GAP", Map.of("from", from, "to", cur.ts(), "minutes", (cur.ts() - from) / 60));
            gapStart = 0;
        }
        boolean poolValid = prev != null && prev.ok() && cur.ts() - prev.ts() == 60;
        if (!started()) {
            // 所有策略和基准同一分钟开始；需要永续报价（对冲策略开空单用）
            if (cur.perpOk()) start(cur);
        } else {
            for (Strategy s : strategies) {
                PositionState st = states.get(s.name());
                if (st.started) step(s, st, cur, poolValid && (!s.hedged() || cur.perpOk()));
            }
        }
        prev = cur;
    }

    private void noteCosts(Snapshot s) {
        if (!s.ok()) return;
        if (s.gasPriceWei() != null) {
            lastGasWei = s.gasPriceWei();
            lastGasTs = s.ts();
        }
        if (s.l1FeeWei() != null) {
            lastL1Wei = s.l1FeeWei();
            lastL1Ts = s.ts();
        }
    }

    /** 一次调仓的 [L2 执行费, L1 数据费]（wei）；1 小时内都没有有效值返回 null。 */
    double[] chainCostWei(long ts) {
        if (lastGasWei == null || ts - lastGasTs > COST_MAX_AGE || lastL1Wei == null || ts - lastL1Ts > COST_MAX_AGE) return null;
        return new double[] {lastGasWei.doubleValue() * rebalanceGas, lastL1Wei.doubleValue()};
    }

    private void notePerp(Snapshot s) {
        if (s.perpOk()) {
            lastRate = s.fundingRate();
            lastRateTs = s.ts();
            lastMark = s.perpMark();
        }
    }

    private void start(Snapshot cur) {
        double sqrt = LpMath.sqrtRaw(cur.sqrtPriceX96());
        double price = LpMath.ethPriceAtSqrt(pool, sqrt);
        for (Strategy s : strategies) {
            PositionState st = states.get(s.name());
            st.started = true;
            st.startTs = cur.ts();
            st.startPrice = price;
            st.lastFundingBoundary = cur.ts() - Math.floorMod(cur.ts(), FUNDING_PERIOD);
            switch (s.kind()) {
                case HOLD_5050 -> {
                    st.holdUsdc = Strategy.CAPITAL / 2;
                    st.holdWeth = Strategy.CAPITAL / 2 / price;
                }
                case HOLD_USDC -> st.holdUsdc = Strategy.CAPITAL;
                case HOLD_ETH -> st.holdWeth = Strategy.CAPITAL / price;
                case LP -> {
                    double capital = Strategy.CAPITAL;
                    if (s.hedged()) {
                        // 做市资金 X + 保证金 X × f / 3 = 本金，f = 仓位里 ETH 价值占比
                        int[] r = LpMath.rangeTicks(pool, sqrt, s.width());
                        double[] unit = LpMath.wethUsdc(pool, 1.0, r[0], r[1], sqrt);
                        double f = unit[0] * price / (unit[0] * price + unit[1]);
                        capital = Strategy.CAPITAL / (1 + f / LEVERAGE);
                        st.initMargin = Strategy.CAPITAL - capital;
                        st.margin = st.initMargin;
                    }
                    openRange(s, st, cur, sqrt, capital);
                }
            }
            ledger.event(cur.ts(), s.name(), "START", Map.of("price", price, "capital", Strategy.CAPITAL, "margin", st.initMargin));
            if (s.hedged()) adjustHedge(s, st, cur, sqrt);
            mark(s, st, cur, sqrt);
        }
    }

    /** 以当前价格为中心开 ±width 的区间，资金 capital（USDC）。 */
    private void openRange(Strategy s, PositionState st, Snapshot cur, double sqrt, double capital) {
        int[] r = LpMath.rangeTicks(pool, sqrt, s.width());
        st.tickLower = r[0];
        st.tickUpper = r[1];
        st.liquidity = LpMath.liquidityFor(pool, capital, r[0], r[1], sqrt);
        double[] wu = LpMath.wethUsdc(pool, st.liquidity, r[0], r[1], sqrt);
        st.segWeth = wu[0];
        st.segUsdc = wu[1];
        st.outSince = 0;
        st.positionId = ledger.openPosition(s.name(), pool.key(), r[0], r[1], st.liquidity, cur.ts());
    }

    private void step(Strategy s, PositionState st, Snapshot cur, boolean valid) {
        double sqrt = LpMath.sqrtRaw(cur.sqrtPriceX96());
        if (valid) st.validMinutes++;
        if (s.kind() == Strategy.Kind.LP && valid) {
            st.fees += feeUsdc(pool, prev, cur, st.tickLower, st.tickUpper, st.liquidity);
            maybeRebalance(s, st, cur, sqrt);
            if (s.hedged()) {
                checkLiquidation(s, st, cur);   // 先按这一分钟的价格检查原来的空单，再调整
                adjustHedge(s, st, cur, sqrt);
            }
        }
        mark(s, st, cur, sqrt);
    }

    private void maybeRebalance(Strategy s, PositionState st, Snapshot cur, double sqrt) {
        if (LpMath.inRange(cur.tick(), st.tickLower, st.tickUpper)) {
            st.outSince = 0;
            return;
        }
        if (st.outSince == 0) st.outSince = cur.ts();
        if (cur.ts() - st.outSince < s.cooldownMinutes() * 60L) return;
        double[] chain = chainCostWei(cur.ts());
        if (chain == null) {   // 1 小时内没有有效的 gas / L1 费：这一分钟不调仓
            if (!costGap) ledger.event(cur.ts(), pool.key(), "COST_GAP", Map.of("gasTs", lastGasTs, "l1Ts", lastL1Ts));
            costGap = true;
            return;
        }
        costGap = false;
        double price = LpMath.ethPriceAtSqrt(pool, sqrt);
        double lp = LpMath.value(pool, st.liquidity, st.tickLower, st.tickUpper, sqrt);
        double l1 = chain[1] / 1e18 * price;
        double gas = chain[0] / 1e18 * price + l1;
        double swap = lp / 2 * (pool.feeRate() + SLIPPAGE);
        double capital = lp - gas - swap;
        if (!(capital > 0)) return;
        int oldLower = st.tickLower, oldUpper = st.tickUpper;
        st.ilRealized += lp - (st.segWeth * price + st.segUsdc);
        ledger.closePosition(st.positionId, cur.ts());
        openRange(s, st, cur, sqrt, capital);
        st.gasCost += gas;
        st.l1Cost += l1;
        st.swapCost += swap;
        st.rebalances++;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("price", price);
        d.put("from", List.of(oldLower, oldUpper));
        d.put("to", List.of(st.tickLower, st.tickUpper));
        d.put("value", lp);
        d.put("gas", gas);
        d.put("l1", l1);
        d.put("swap", swap);
        ledger.event(cur.ts(), s.name(), "REBALANCE", d);
    }

    /** 空单数量向仓位里的 ETH 数量靠拢：偏离超过仓位价值 2% 才调整，按标记价格成交、扣 taker 手续费。 */
    private void adjustHedge(Strategy s, PositionState st, Snapshot cur, double sqrt) {
        if (st.liquidated || !cur.perpOk()) return;
        double mark = cur.perpMark();
        double lp = LpMath.value(pool, st.liquidity, st.tickLower, st.tickUpper, sqrt);
        double target = LpMath.wethUsdc(pool, st.liquidity, st.tickLower, st.tickUpper, sqrt)[0];
        double diff = target - st.hedgeQty;
        if (Math.abs(diff) * mark <= HEDGE_BAND * lp) return;
        double fee = Math.abs(diff) * mark * TAKER;
        if (diff > 0) {
            st.hedgeEntry = (st.hedgeQty * st.hedgeEntry + diff * mark) / (st.hedgeQty + diff);
        } else {
            double realized = -diff * (st.hedgeEntry - mark);
            st.hedgeRealized += realized;
            st.margin += realized;
        }
        st.hedgeQty = target;
        if (st.hedgeQty <= 0) {
            st.hedgeQty = 0;
            st.hedgeEntry = 0;
        }
        st.margin -= fee;
        st.hedgeFees += fee;
        st.swapCost += fee;
        st.hedgeTrades++;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("mark", mark);
        d.put("qty", st.hedgeQty);
        d.put("delta", diff);
        d.put("fee", fee);
        ledger.event(cur.ts(), s.name(), "HEDGE", d);
    }

    /** 强平价 = (保证金 + 数量 × 均价) / (数量 × (1 + 维持保证金率))。 */
    static double liquidationPrice(double margin, double qty, double entry) {
        return qty > 0 ? (margin + qty * entry) / (qty * (1 + MAINTENANCE)) : Double.POSITIVE_INFINITY;
    }

    private void checkLiquidation(Strategy s, PositionState st, Snapshot cur) {
        if (st.liquidated || st.hedgeQty <= 0 || !cur.perpOk()) {
            st.atRisk = false;
            return;
        }
        double mark = cur.perpMark();
        double liq = liquidationPrice(st.margin, st.hedgeQty, st.hedgeEntry);
        if (mark >= liq) {
            double pnl = st.hedgeQty * (st.hedgeEntry - mark);
            st.hedgeRealized += pnl;
            st.margin = 0;
            st.hedgeQty = 0;
            st.hedgeEntry = 0;
            st.liquidated = true;
            st.atRisk = true;
            st.riskEvents++;
            ledger.event(cur.ts(), s.name(), "LIQUIDATION", Map.of("mark", mark, "liquidationPrice", liq));
            return;
        }
        double distance = liq / mark - 1;
        if (distance < RISK_DISTANCE) {
            if (!st.atRisk) {
                st.riskEvents++;
                ledger.event(cur.ts(), s.name(), "LIQ_RISK", Map.of("mark", mark, "liquidationPrice", liq, "distance", distance));
            }
            st.atRisk = true;
        } else {
            st.atRisk = false;
        }
    }

    /**
     * 资金费：每过一个结算点（UTC 00/08/16）结算一次，费率用结算点之前 8 小时内最后看到的 lastFundingRate，
     * 价格用当前标记价（没有就用最近看到的）。资金费是持仓的被动现金流，池子数据缺失时也照常结算；
     * 结算点前 8 小时内一次报价都没有时记 FUNDING_GAP，不结算。
     */
    private void settleFunding(Snapshot cur) {
        long boundary = cur.ts() - Math.floorMod(cur.ts(), FUNDING_PERIOD);
        Double mark = cur.perpOk() ? cur.perpMark() : lastMark;
        boolean rateKnown = lastRate != null && lastRateTs < boundary && lastRateTs >= boundary - FUNDING_PERIOD && mark != null;
        for (Strategy s : strategies) {
            if (!s.hedged()) continue;
            PositionState st = states.get(s.name());
            if (!st.started || boundary <= st.lastFundingBoundary) continue;
            st.lastFundingBoundary = boundary;
            if (st.hedgeQty <= 0) continue;
            if (!rateKnown) {
                ledger.event(cur.ts(), s.name(), "FUNDING_GAP", Map.of("boundary", boundary));
                continue;
            }
            double amount = st.hedgeQty * mark * lastRate;
            st.margin += amount;
            st.funding += amount;
            ledger.event(cur.ts(), s.name(), "FUNDING", Map.of("boundary", boundary, "rate", lastRate, "mark", mark, "amount", amount));
        }
    }

    /** 按当前价格估值。 */
    private void mark(Strategy s, PositionState st, Snapshot cur, double sqrt) {
        double price = LpMath.ethPriceAtSqrt(pool, sqrt);
        st.lastTs = cur.ts();
        st.lastPrice = price;
        if (s.benchmark()) {
            st.lastLpValue = 0;
            st.lastValue = st.holdWeth * price + st.holdUsdc;
            return;
        }
        double lp = LpMath.value(pool, st.liquidity, st.tickLower, st.tickUpper, sqrt);
        st.lastLpValue = lp;
        st.lastIl = st.ilRealized + (lp - (st.segWeth * price + st.segUsdc));
        double hedgeMark = cur.perpOk() ? cur.perpMark() : price;
        double equity = st.margin + st.hedgeQty * (st.hedgeEntry - hedgeMark);
        st.lastHedgePnl = s.hedged() ? equity - st.initMargin - st.funding + st.hedgeFees : 0;
        st.lastValue = lp + st.fees + (s.hedged() ? equity : 0);
    }

    /** 一个有效区间的手续费（USDC）。 */
    static double feeUsdc(PoolInfo pool, Snapshot a, Snapshot b, int tickLower, int tickUpper, double liquidity) {
        boolean inA = LpMath.inRange(a.tick(), tickLower, tickUpper);
        boolean inB = LpMath.inRange(b.tick(), tickLower, tickUpper);
        double factor = inA && inB ? 1.0 : inA || inB ? 0.5 : 0.0;
        if (factor == 0 || liquidity <= 0) return 0;
        double active = a.liquidity().doubleValue();
        double share = active / (active + liquidity);
        BigInteger d0 = Abi.deltaMod256(b.fg0(), a.fg0());
        BigInteger d1 = Abi.deltaMod256(b.fg1(), a.fg1());
        double fee0 = liquidity * (d0.doubleValue() / LpMath.Q128) * share * factor / Math.pow(10, pool.dec0());
        double fee1 = liquidity * (d1.doubleValue() / LpMath.Q128) * share * factor / Math.pow(10, pool.dec1());
        double price = LpMath.ethPriceAtSqrt(pool, LpMath.sqrtRaw(b.sqrtPriceX96()));
        return pool.wethIsToken0() ? fee0 * price + fee1 : fee0 + fee1 * price;
    }

    List<PositionState> snapshotStates() {
        return new ArrayList<>(states.values());
    }
}
