package com.trendpilot.tracking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 候选退出规则（设计文档第 7.1 节，参数网格事先登记）。每条规则都叠加在现行规则 B0 之上。
 * 判断按收盘价（与 Monitor 看现价一致）；S1/S2 另有"按最低价触发"的敏感性版本（编号带 @L）。
 */
public record ExitRule(String id, String family, Map<String, Double> params, boolean lowTrigger) {

    /**
     * 规则判断需要的状态：净收益、原始收益（%）、持有小时、持有以来最高净收益、相对最高收盘价的回撤（%）、ATR1h%、
     * BTC 最近 1 小时涨跌（%，没有数据为 NaN）。
     */
    public record State(double rNet, double rRaw, double holdHours, double peakNet, double drawdownFromMaxPct,
                        double atrPct, double btcRet1hPct) {

        public State(double rNet, double rRaw, double holdHours, double peakNet, double drawdownFromMaxPct,
                     double atrPct) {
            this(rNet, rRaw, holdHours, peakNet, drawdownFromMaxPct, atrPct, Double.NaN);
        }
    }

    double v(String k) {
        return params.get(k);
    }

    /** @return 触发时的退出原因，否则 null */
    public String check(State s) {
        return switch (family) {
            case "S1" -> s.rNet() <= -v("L") ? id : null;
            case "S2" -> !Double.isNaN(s.atrPct()) && s.rRaw() <= -v("k") * s.atrPct() ? id : null;
            case "T1" -> s.holdHours() >= v("T") && s.rNet() < v("g") ? id : null;
            case "T2" -> s.holdHours() >= v("T") && s.rNet() <= -v("L2") ? id : null;
            case "P1" -> s.peakNet() >= v("a") && s.drawdownFromMaxPct() >= v("D") ? id : null;
            case "C1" -> s.rNet() <= -v("L") || (s.holdHours() >= v("T") && s.rNet() < 0) ? id : null;
            // 紧急退出：BTC 1 小时跌幅达到 D% 时全部卖出（只做影子对比，用户规则仍是不止损）
            case "M1" -> !Double.isNaN(s.btcRet1hPct()) && s.btcRet1hPct() <= -v("D") ? id : null;
            default -> null;
        };
    }

    /** 按最低价触发的止损价（只用于 S1/S2 的 @L 版本）；返回 NaN 表示不适用。 */
    public double stopPrice(PathCalculator.Cost c, double atrPct) {
        if (!lowTrigger) return Double.NaN;
        return switch (family) {
            case "S1" -> c.priceAtNet(-v("L"));
            case "S2" -> Double.isNaN(atrPct) ? Double.NaN : c.p0() * (1 - v("k") * atrPct / 100);
            default -> Double.NaN;
        };
    }

    public String paramsJson() {
        StringBuilder b = new StringBuilder("{");
        params.forEach((k, val) -> b.append(b.length() > 1 ? "," : "").append('"').append(k).append("\":").append(val));
        return b.append(lowTrigger ? ",\"lowTrigger\":true}" : "}").toString();
    }

    private static ExitRule of(String family, boolean low, Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        StringBuilder id = new StringBuilder(family);
        for (int i = 0; i < kv.length; i += 2) {
            double val = ((Number) kv[i + 1]).doubleValue();
            m.put((String) kv[i], val);
            id.append('_').append(kv[i]).append(val == Math.rint(val) ? String.valueOf((long) val)
                    : String.format(Locale.ROOT, "%s", val));
        }
        if (low) id.append("@L");
        return new ExitRule(id.toString(), family, m, low);
    }

    /** 事先登记的全部候选（39 组 + S1/S2 的按最低价触发敏感性版本 + 3 组 BTC 急跌紧急退出 M1）。 */
    public static List<ExitRule> grid() {
        List<ExitRule> out = new ArrayList<>();
        for (boolean low : new boolean[]{false, true}) {
            for (int l : new int[]{8, 10, 12, 15, 20, 25}) out.add(of("S1", low, "L", l));
            for (int k : new int[]{4, 6, 8, 10}) out.add(of("S2", low, "k", k));
        }
        for (int t : new int[]{12, 24, 48, 72}) for (int g : new int[]{0, 2}) out.add(of("T1", false, "T", t, "g", g));
        for (int t : new int[]{24, 48}) for (int l : new int[]{5, 10}) out.add(of("T2", false, "T", t, "L2", l));
        for (int a : new int[]{5, 10}) for (int d : new int[]{10, 15, 20, 25}) out.add(of("P1", false, "a", a, "D", d));
        for (int l : new int[]{15, 20, 25}) for (int t : new int[]{24, 48, 72}) out.add(of("C1", false, "L", l, "T", t));
        for (int d : new int[]{3, 5, 8}) out.add(of("M1", false, "D", d));
        return out;
    }

    public static ExitRule byId(String id) {
        return grid().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的候选退出规则：" + id));
    }
}
