package com.trendpilot.backtest;

/**
 * 防洗盘止损的候选规则（研究用，不接线上）。叠加在现行 V2 退出规则之上，只用触发时已收盘的 K 线。
 *
 * <ul>
 * <li>距离：固定百分比，或买入时日线 ATR × k（限制在 [min, max]）。</li>
 * <li>相对 BTC：只有"这个币比 BTC 多跌了 d"且自身也亏了至少 minAbs 才算跌破，跟着 BTC 一起跌不算。</li>
 * <li>BTC/山寨同步下跌暂缓：BTC 4h ≤ −1.5% 或山寨 4h 中位数 ≤ −3% 时不止损，等大盘跌势停下再判断。</li>
 * <li>确认：15m 收盘 / 1h 收盘 / 连续两根 1h 收盘都在线下。</li>
 * <li>灾难线：无论上面怎么暂缓，按 1h 收盘跌破 −floor 一定卖。</li>
 * </ul>
 *
 * @param id        方案名（报告里显示）
 * @param fixedPct  固定距离（%），> 0 时不用 ATR
 * @param atrK      日线 ATR 倍数
 * @param minPct    距离下限（%）
 * @param maxPct    距离上限（%）
 * @param relative  按相对 BTC 的跌幅判断
 * @param minAbsPct 相对模式下自身至少亏这么多（%）才卖
 * @param pause     大盘同步下跌时暂缓
 * @param confirm   BAR15 / HOUR / HOUR2
 * @param floorPct  灾难线（%），0 = 不设
 */
public record StopRule(String id, double fixedPct, double atrK, double minPct, double maxPct, boolean relative,
                       double minAbsPct, boolean pause, String confirm, double floorPct) {

    public static StopRule fixed(String id, double pct, String confirm) {
        return new StopRule(id, pct, 0, pct, pct, false, 0, false, confirm, 0);
    }

    public static StopRule atr(String id, double k, boolean relative, boolean pause, String confirm) {
        return new StopRule(id, 0, k, 6, 25, relative, relative ? 5 : 0, pause, confirm, 30);
    }

    /** 买入时的止损距离（比例）。 */
    public double distance(double atrDailyPct) {
        if (fixedPct > 0) return fixedPct / 100;
        double d = Double.isNaN(atrDailyPct) ? (minPct + maxPct) / 2 : atrK * atrDailyPct;
        return Math.max(minPct, Math.min(maxPct, d)) / 100;
    }

    public double fallback() {
        return distance(Double.NaN);
    }

    /** 第 q 根收盘时是否止损；返回卖出原因或 null。 */
    public String check(MarketData data, Series s, int q, double avg, int entryQ, double dist) {
        boolean hourClose = q % 4 == 3;
        if (!confirm.equals("BAR15") && !hourClose) return null;
        double c = s.close[q];
        double ret = c / avg - 1;
        if (floorPct > 0 && hourClose && ret <= -floorPct / 100) return "L1_FLOOR";
        if (!below(data, s, q, avg, entryQ, dist)) return null;
        if (confirm.equals("HOUR2") && !(s.has(q - 4) && below(data, s, q - 4, avg, entryQ, dist))) return null;
        if (pause) {
            MarketData.Context ctx = data.context(q + 1); // q 收盘后的整点
            if (ctx != null && (ctx.btc4h() <= -1.5 || ctx.alt4h() <= -3)) return null;
        }
        return "L1_STOP";
    }

    private boolean below(MarketData data, Series s, int q, double avg, int entryQ, double dist) {
        if (!s.has(q)) return false;
        double ret = s.close[q] / avg - 1;
        if (!relative) return ret <= -dist;
        Series btc = data.btc;
        if (!btc.has(q) || !btc.has(entryQ)) return ret <= -dist;
        double btcRet = btc.close[q] / btc.close[entryQ] - 1;
        return ret <= -minAbsPct / 100 && ret - btcRet <= -dist;
    }
}
