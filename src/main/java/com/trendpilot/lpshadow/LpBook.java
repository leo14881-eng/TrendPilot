package com.trendpilot.lpshadow;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个池子的全部虚拟仓位和基准，逐分钟吃快照（纯计算，不读写数据库；持久化通过 {@link Ledger}）。
 *
 * <p>有效区间：上一条和这一条快照都是 OK 且正好相隔 60 秒。只有有效区间才计手续费；
 * 中间缺数据（GAP 行或缺分钟）的那一段不计费、不动作，只记一条池子级别的 GAP 事件。
 *
 * <p>手续费（每个有效区间）：开始和结束时 tick 都在 [tickLower, tickUpper) 内时
 * fee0 = L × Δfg0 / 2^128 × L_active / (L_active + L)，fee1 同理（Δfg 按 mod 2^256 求差，L_active 取区间开始时的值）；
 * 只有一端在区间内按一半计；都不在不计。按结束时的价格换算成 USDC 单独累计，不复投。
 */
final class LpBook {

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
    Snapshot prev;
    long gapStart;

    LpBook(PoolInfo pool, List<Strategy> strategies, Map<String, PositionState> restored, Snapshot prev, Ledger ledger) {
        this.pool = pool;
        this.strategies = strategies;
        this.ledger = ledger;
        this.prev = prev;
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
        boolean valid = prev != null && prev.ok() && cur.ts() - prev.ts() == 60;
        if (!started()) start(cur);
        else {
            for (Strategy s : strategies) {
                PositionState st = states.get(s.name());
                if (st.started) step(s, st, cur, valid);
            }
        }
        prev = cur;
    }

    private void start(Snapshot cur) {
        double sqrt = LpMath.sqrtRaw(cur.sqrtPriceX96());
        double price = LpMath.ethPriceAtSqrt(pool, sqrt);
        for (Strategy s : strategies) {
            if (s.hedged()) continue;   // 对冲策略在下一步（对冲、资金费）上线时一起开始
            PositionState st = states.get(s.name());
            st.started = true;
            st.startTs = cur.ts();
            st.startPrice = price;
            switch (s.kind()) {
                case HOLD_5050 -> {
                    st.holdUsdc = Strategy.CAPITAL / 2;
                    st.holdWeth = Strategy.CAPITAL / 2 / price;
                }
                case HOLD_USDC -> st.holdUsdc = Strategy.CAPITAL;
                case HOLD_ETH -> st.holdWeth = Strategy.CAPITAL / price;
                case LP -> openRange(s, st, cur, sqrt, Strategy.CAPITAL);
            }
            ledger.event(cur.ts(), s.name(), "START", Map.of("price", price, "capital", Strategy.CAPITAL));
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
        if (s.kind() == Strategy.Kind.LP && valid) {
            st.fees += feeUsdc(pool, prev, cur, st.tickLower, st.tickUpper, st.liquidity);
            st.validMinutes++;
        } else if (valid) {
            st.validMinutes++;
        }
        mark(s, st, cur, sqrt);
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
        st.lastValue = lp + st.fees;
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
