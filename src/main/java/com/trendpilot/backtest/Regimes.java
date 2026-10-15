package com.trendpilot.backtest;

/**
 * 六种行情（V2交易规则重设计 7.3），每个整点只用当时已知的数据判定：
 * <ol>
 * <li>普涨：BTC 7 天涨 ≥ 5%，且山寨 7 天中位数也在涨</li>
 * <li>山寨强于 BTC：山寨 7 天中位数比 BTC 多涨 ≥ 5 个百分点</li>
 * <li>横盘震荡：BTC 7 天在 ±3% 内，山寨 7 天中位数在 ±5% 内</li>
 * <li>山寨阴跌：山寨 24h 中位数 ≤ −3%，且不在暴跌期</li>
 * <li>暴跌：最近 24 小时内出现过山寨 1h 中位数 ≤ −5%</li>
 * <li>暴跌后反弹：暴跌后第 2～7 天</li>
 * </ol>
 * 判定顺序 5 → 6 → 4 → 2 → 1 → 3；都不符合的归入最接近的一种：山寨比 BTC 多涨 ≥ 2.5 点归 2，
 * BTC 7 天涨 ≥ 2.5% 归 1，山寨 24h ≤ −1.5% 或 7 天 ≤ −5% 归 4，其余归 3。
 */
public final class Regimes {

    public static final String[] NAMES = {"", "普涨", "山寨强于BTC", "横盘震荡", "山寨阴跌", "暴跌", "暴跌后反弹"};

    private final int[] byHour;

    public Regimes(MarketData data) {
        int hours = data.size / 4 + 1;
        byHour = new int[hours];
        int lastCrash = -100_000;
        for (int b = 0; b < hours; b++) {
            MarketData.Context c = data.contextAtHour(b);
            if (c == null) continue;
            if (!Double.isNaN(c.alt1h()) && c.alt1h() <= -5) lastCrash = b;
            byHour[b] = classify(c, b - lastCrash);
        }
    }

    static int classify(MarketData.Context c, int hoursSinceCrash) {
        if (hoursSinceCrash < 24) return 5;
        if (hoursSinceCrash < 7 * 24) return 6;
        if (c.alt24h() <= -3) return 4;
        double excess = c.alt7d() - c.btc7d();
        if (excess >= 5) return 2;
        if (c.btc7d() >= 5 && c.alt7d() > 0) return 1;
        if (Math.abs(c.btc7d()) <= 3 && Math.abs(c.alt7d()) <= 5) return 3;
        if (excess >= 2.5) return 2;
        if (c.btc7d() >= 2.5) return 1;
        if (c.alt24h() <= -1.5 || c.alt7d() <= -5) return 4;
        return 3;
    }

    /** 第 q 根 15m 所在整点的行情编号（1～6，数据不足为 0）。 */
    public int at(int q) {
        return byHour[q / 4];
    }

    public int hours(int regime, int fromQ, int toQ) {
        int n = 0;
        for (int b = fromQ / 4; b < toQ / 4 && b < byHour.length; b++) if (byHour[b] == regime) n++;
        return n;
    }
}
