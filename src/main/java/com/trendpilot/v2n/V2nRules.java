package com.trendpilot.v2n;

/**
 * 新版 V2 的纯计算规则（不碰账本、不发请求），回测和将来的影子并行共用。价格单位与行情一致，百分比为百分点。
 */
public final class V2nRules {

    private V2nRules() {
    }

    public enum AltState {
        NORMAL, SEMI, WEAK
    }

    /** 山寨大盘门：24h 中位数或趋势占比任一触线即生效；数据缺失按正常（由其他风控兜底）。 */
    public static AltState altState(V2nParams p, double alt24hMedian, double trendShare) {
        if (!p.altGate()) return AltState.NORMAL;
        if ((!Double.isNaN(alt24hMedian) && alt24hMedian <= p.altWeakPct())
                || (!Double.isNaN(trendShare) && trendShare < p.altWeakTrend())) {
            return AltState.WEAK;
        }
        if ((!Double.isNaN(alt24hMedian) && alt24hMedian <= p.altSemiPct())
                || (!Double.isNaN(trendShare) && trendShare < p.altSemiTrend())) {
            return AltState.SEMI;
        }
        return AltState.NORMAL;
    }

    /** 币自身在上涨：1h 收盘 > 1h EMA20 > 1h EMA50，且 4h 收盘 > 4h EMA20。指标缺失视为不满足。 */
    public static boolean trendOk(double price, double ema20h, double ema50h, double ema20x4h) {
        if (Double.isNaN(ema20h) || Double.isNaN(ema50h) || Double.isNaN(ema20x4h)) return false;
        return price > ema20h && ema20h > ema50h && price > ema20x4h;
    }

    /** 拉伸度 = (现价 − 1h EMA20) ÷ 1h ATR（绝对值）。 */
    public static double extension(double price, double ema20h, double atrAbs) {
        if (Double.isNaN(ema20h) || !(atrAbs > 0)) return Double.NaN;
        return (price - ema20h) / atrAbs;
    }

    /** 止损距离（%）；超过上限返回 NaN（太疯，不买）。 */
    public static double stopPct(V2nParams p, double atrPct) {
        if (!(atrPct > 0)) return Double.NaN;
        double d = p.stopAtr() * atrPct;
        if (d > p.maxStopPct()) return Double.NaN;
        return Math.max(d, p.minStopPct());
    }

    /** 本笔金额 = R × 风险系数 ÷ 止损距离，限制在 [minSize, maxSize]。 */
    public static double size(V2nParams p, double stopPct, double riskMultiplier) {
        if (p.fixedSize() > 0) return p.fixedSize();
        double s = p.riskUnit() * riskMultiplier / (stopPct / 100);
        return Math.max(p.minSize(), Math.min(p.maxSize(), s));
    }

    /**
     * 可退出净利等于 targetNet 的价格：qty × x × (1 − 冲击) × (1 − 手续费) − 成本 = targetNet。
     */
    public static double priceForNet(double qty, double cost, double impact, double feeRate, double targetNet) {
        return (cost + targetNet) / (qty * (1 - impact) * (1 - feeRate));
    }

    public static double netAt(double qty, double cost, double impact, double feeRate, double price) {
        return qty * price * (1 - impact) * (1 - feeRate) - cost;
    }

    /** 跟踪止损：最高收盘价 − m × ATR；净利到 tightenR 以上用更紧的倍数。 */
    public static double trailStop(V2nParams p, double highestClose, double atrAbs, double peakNetR) {
        double m = peakNetR >= p.tightenR() ? p.tightTrailAtr() : p.trailAtr();
        return highestClose - m * atrAbs;
    }
}
