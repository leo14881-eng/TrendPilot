package com.trendpilot.backtest;

import java.util.ArrayList;
import java.util.List;

import com.trendpilot.backtest.PrePumpStudy.Sign;

/**
 * 用合约数据和成交结构找"大涨前"的迹象：资金费率、持仓量、多空比、主动买卖、合约/现货新上线。
 * 门槛是第一次的直觉值，没有调过参（避免在同一份数据上挑最好的门槛）。
 */
final class DerivSigns {

    static final int DAY = MarketData.DAY;
    static final int H = 4;

    private DerivSigns() {
    }

    static List<Sign> signs(FuturesData fd) {
        List<Sign> t = new ArrayList<>();
        t.add(new Sign("对照 A：随便买（所有币，每天一次）", (s, q, c) -> q % DAY == DAY - 1, true));
        t.add(new Sign("对照 B：随便买（有合约的币，每天一次）", (s, q, c) -> q % DAY == DAY - 1 && hasFunding(fd, s, q), true));
        t.add(new Sign("对照 C：随便买（有持仓数据的币，2025 年起，每天一次）", (s, q, c) -> q % DAY == DAY - 1 && hasOi(fd, s, q), true));
        t.add(new Sign("资金费率 ≤ -0.03%（空头付钱）、24h 价格 ±5%", (s, q, c) -> funding(fd, s, q) <= -0.03 && Math.abs(PumpStudy.ret(s, q, DAY)) < 5));
        t.add(new Sign("资金费率 ≤ -0.1%（空头很拥挤）", (s, q, c) -> funding(fd, s, q) <= -0.1));
        t.add(new Sign("持仓价值 24h 增 ≥20%、24h 价格 ±3%", (s, q, c) -> oiChange(fd, s, q, 24) >= 20 && Math.abs(PumpStudy.ret(s, q, DAY)) < 3));
        t.add(new Sign("持仓价值 4h 增 ≥10%、4h 价格 ±2%", (s, q, c) -> oiChange(fd, s, q, 4) >= 10 && Math.abs(PumpStudy.ret(s, q, 4 * H)) < 2));
        t.add(new Sign("散户多空比 ≤0.8（多数人看空）、持仓 24h 增 ≥10%", (s, q, c) -> value(fd, s, q, 1) <= 0.8 && oiChange(fd, s, q, 24) >= 10));
        t.add(new Sign("大户多空比 24h 升 ≥20%、24h 价格 ±3%", (s, q, c) -> change(fd, s, q, 2, 24) >= 20 && Math.abs(PumpStudy.ret(s, q, DAY)) < 3));
        t.add(new Sign("合约主动买/卖 4h 平均 ≥1.3、4h 价格 ±2%", (s, q, c) -> avg(fd, s, q, 3, 4) >= 1.3 && Math.abs(PumpStudy.ret(s, q, 4 * H)) < 2));
        t.add(new Sign("现货主动买入占比 4h ≥60%、4h 价格 ±2%", (s, q, c) -> s.takerBuyShare(q, 4 * H) >= 0.60 && Math.abs(PumpStudy.ret(s, q, 4 * H)) < 2));
        t.add(new Sign("现货主动买入占比 24h ≥57%、24h 价格 ±3%", (s, q, c) -> s.takerBuyShare(q, DAY) >= 0.57 && Math.abs(PumpStudy.ret(s, q, DAY)) < 3));
        t.add(new Sign("合约刚上线 24 小时内（现货已上线 ≥30 天）", (s, q, c) -> {
            FuturesData.Fut f = fd.get(s.symbol);
            if (f == null || f.listedHour() < 0) return false;
            int since = q / 4 - f.listedHour();
            return since >= 0 && since <= 24 && s.firstIndex() >= 0 && q - s.firstIndex() >= 30 * DAY;
        }));
        t.add(new Sign("现货上线 1~3 天", (s, q, c) -> newListing(s, q, 1, 3)));
        t.add(new Sign("现货上线 4~10 天", (s, q, c) -> newListing(s, q, 4, 10)));
        return t;
    }

    /** 上线（数据里第一根 K 线，且晚于数据开始 7 天以上）后第 fromDay ~ toDay 天。 */
    static boolean newListing(Series s, int q, int fromDay, int toDay) {
        int f = s.firstIndex();
        if (f < 7 * DAY) return false;
        int age = q - f;
        return age >= fromDay * DAY - DAY && age < toDay * DAY;
    }

    private static int hour(int q) {
        return q / 4;
    }

    static boolean hasFunding(FuturesData fd, Series s, int q) {
        return !Double.isNaN(funding(fd, s, q));
    }

    static boolean hasOi(FuturesData fd, Series s, int q) {
        FuturesData.Fut f = fd.get(s.symbol);
        int h = hour(q);
        return f != null && h < f.oiValue.length && !Float.isNaN(f.oiValue[h]);
    }

    static double funding(FuturesData fd, Series s, int q) {
        FuturesData.Fut f = fd.get(s.symbol);
        int h = hour(q);
        return f == null || h >= f.funding.length ? Double.NaN : f.funding[h];
    }

    /** kind：0 持仓价值，1 散户多空比，2 大户多空比，3 合约主动买卖比。 */
    static float[] arr(FuturesData.Fut f, int kind) {
        return switch (kind) {
            case 0 -> f.oiValue;
            case 1 -> f.ls;
            case 2 -> f.topLs;
            default -> f.takerLs;
        };
    }

    static double value(FuturesData fd, Series s, int q, int kind) {
        FuturesData.Fut f = fd.get(s.symbol);
        int h = hour(q);
        if (f == null || h >= f.oiValue.length) return Double.NaN;
        return arr(f, kind)[h];
    }

    /** 最近 hours 小时的变化（%）。 */
    static double change(FuturesData fd, Series s, int q, int kind, int hours) {
        FuturesData.Fut f = fd.get(s.symbol);
        int h = hour(q);
        if (f == null || h >= f.oiValue.length || h - hours < 0) return Double.NaN;
        float[] a = arr(f, kind);
        double now = a[h], then = a[h - hours];
        return Double.isNaN(now) || Double.isNaN(then) || then <= 0 ? Double.NaN : (now / then - 1) * 100;
    }

    static double oiChange(FuturesData fd, Series s, int q, int hours) {
        return change(fd, s, q, 0, hours);
    }

    /** 最近 hours 小时的平均（缺的小时跳过，缺一半以上返回 NaN）。 */
    static double avg(FuturesData fd, Series s, int q, int kind, int hours) {
        FuturesData.Fut f = fd.get(s.symbol);
        int h = hour(q);
        if (f == null || h >= f.oiValue.length) return Double.NaN;
        float[] a = arr(f, kind);
        double sum = 0;
        int n = 0;
        for (int i = Math.max(0, h - hours + 1); i <= h; i++) {
            if (!Float.isNaN(a[i])) {
                sum += a[i];
                n++;
            }
        }
        return n * 2 < hours ? Double.NaN : sum / n;
    }
}
