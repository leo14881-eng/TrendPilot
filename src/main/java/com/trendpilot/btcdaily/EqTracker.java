package com.trendpilot.btcdaily;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 研究用：按 U 和按 BTC 两种口径记录账本权益。权益 U = 资金 + 已实现 + 浮动；权益 BTC = 权益 U / 当时价格。
 * 按季度记录起止权益和价格，便于按大涨 / 大跌 / 震荡阶段分开统计 U 收益和 BTC 增量。
 */
public final class EqTracker {

    public final double cap;
    public double startPrice = Double.NaN, lastPrice = Double.NaN, lastEq;
    /** 季度 → {期初权益 U, 期初价格, 期末权益 U, 期末价格} */
    public final Map<String, double[]> quarters = new LinkedHashMap<>();
    /** 每天收盘时的 {权益 U, 价格} */
    public final Map<LocalDate, double[]> days = new LinkedHashMap<>();

    public EqTracker(double cap) {
        this.cap = cap;
        this.lastEq = cap;
    }

    public static String quarter(LocalDate d) {
        return d.getYear() + "Q" + ((d.getMonthValue() - 1) / 3 + 1);
    }

    /** 每根 K 线收盘调用：eqU 为当时总权益（U），px 为收盘价；open 为本根开盘价（季度第一根用它做期初价）。 */
    public void on(LocalDate day, double open, double eqU, double px) {
        if (Double.isNaN(startPrice)) startPrice = open;
        double[] q = quarters.get(quarter(day));
        if (q == null) {
            q = new double[]{lastEq, Double.isNaN(lastPrice) ? open : lastPrice, eqU, px};
            quarters.put(quarter(day), q);
        }
        q[2] = eqU;
        q[3] = px;
        lastEq = eqU;
        lastPrice = px;
        days.put(day, new double[]{eqU, px});
    }

    /** 区间 [f, t] 内：{权益 U 增量, BTC 涨幅, 捕获比例 = 权益增幅 / BTC 涨幅}；取不到返回 null。 */
    public double[] segment(LocalDate f, LocalDate t) {
        double[] a = null, b = null;
        for (var e : days.entrySet()) {
            if (e.getKey().isBefore(f.minusDays(1))) continue;
            if (e.getKey().isAfter(t)) break;
            if (a == null) a = e.getValue();
            b = e.getValue();
        }
        if (a == null || b == null) return null;
        double btc = b[1] / a[1] - 1;
        return new double[]{b[0] - a[0], btc, btc == 0 ? 0 : (b[0] / a[0] - 1) / btc};
    }

    public double btcStart() {
        return cap / startPrice;
    }

    public double btcEnd() {
        return lastEq / lastPrice;
    }

    /** 权益折算 BTC 相对期初（全部换成 BTC 的数量）的变化，%。 */
    public double btcChangePct() {
        return (btcEnd() / btcStart() - 1) * 100;
    }

    /** 按季度 BTC 涨跌分阶段：≥ +20% 大涨，≤ −20% 大跌，其余震荡。返回 阶段 → {季度数, U 收益, BTC 增量}。 */
    public Map<String, double[]> phases() {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (String k : new String[]{"大涨", "大跌", "震荡"}) out.put(k, new double[3]);
        for (double[] q : quarters.values()) {
            double r = q[3] / q[1] - 1;
            String k = r >= 0.20 ? "大涨" : r <= -0.20 ? "大跌" : "震荡";
            double[] v = out.get(k);
            v[0]++;
            v[1] += q[2] - q[0];
            v[2] += q[2] / q[3] - q[0] / q[1];
        }
        return out;
    }
}
