package com.trendpilot.variant;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一组相对 V2 基准（application.yml）的参数改动，配置化影子变体、自动转正和回测共用同一份判断代码。
 * 所有字段为 null 表示"不改"，全部为 null 时与 V2 完全相同。
 * <ul>
 * <li>审核：{@code min-buy-rel1h}（新开仓 1h 相对 BTC 门槛 %）、{@code min-depth-usdt}（±2% 深度门槛）、{@code trade-usdt}（每笔金额）。</li>
 * <li>H1 追高过滤：{@code btc24h-above-pct}（BTC 24h 涨幅高于此值）或 {@code coin24h-band-pct}（币 24h 涨幅落在 "5-10" 区间）时，
 *     按 {@code hot-entry}（SKIP 不开仓 / HALF 半仓，默认 SKIP）处理。</li>
 * <li>H4 放量上限：{@code max-volume-accel}，放量倍数高于此值不开仓。</li>
 * <li>H2 从没赚过就早走：持有满 {@code early-exit-hours} 小时、利润保护没启动、持仓期间最大可退出净利 &lt; 本金
 *     {@code early-exit-max-mfe-pct}%、最近 1h 收盘相对均价跌超 {@code early-exit-loss-pct}% 时卖出。</li>
 * </ul>
 * 数据缺失（NaN）时：开仓过滤按"不买"处理，提前离场按"不卖"处理——数据缺失只能导致不动作。
 */
public record VariantOverrides(Double minBuyRel1h, Double minDepthUsdt, Double tradeUsdt,
                               Double btc24hAbovePct, Double coin24hMinPct, Double coin24hMaxPct, HotEntry hotEntry,
                               Double maxVolumeAccel,
                               Double earlyExitHours, Double earlyExitMaxMfePct, Double earlyExitLossPct) {

    public static final String EARLY_EXIT = "EARLY_EXIT";
    public static final VariantOverrides NONE = new VariantOverrides(null, null, null, null, null, null, null, null, null, null, null);

    /** 追高时的处理：不开仓，或按一半金额开仓。 */
    public enum HotEntry { SKIP, HALF }

    public VariantOverrides {
        positive("min-buy-rel1h", minBuyRel1h, true);
        positive("min-depth-usdt", minDepthUsdt, false);
        positive("trade-usdt", tradeUsdt, false);
        positive("max-volume-accel", maxVolumeAccel, false);
        positive("early-exit-hours", earlyExitHours, false);
        positive("early-exit-loss-pct", earlyExitLossPct, false);
        if (earlyExitMaxMfePct != null && !Double.isFinite(earlyExitMaxMfePct)) throw new IllegalArgumentException("early-exit-max-mfe-pct");
        if (btc24hAbovePct != null && !Double.isFinite(btc24hAbovePct)) throw new IllegalArgumentException("btc24h-above-pct");
        if ((coin24hMinPct == null) != (coin24hMaxPct == null) || (coin24hMinPct != null && !(coin24hMinPct < coin24hMaxPct))) {
            throw new IllegalArgumentException("coin24h-band-pct 要写成 \"低-高\"，例如 5-10");
        }
        boolean anyExit = earlyExitHours != null || earlyExitMaxMfePct != null || earlyExitLossPct != null;
        boolean allExit = earlyExitHours != null && earlyExitMaxMfePct != null && earlyExitLossPct != null;
        if (anyExit && !allExit) {
            throw new IllegalArgumentException("early-exit-hours、early-exit-max-mfe-pct、early-exit-loss-pct 要一起设置");
        }
        if (tradeUsdt != null && tradeUsdt > 5000) throw new IllegalArgumentException("trade-usdt 影子变体每笔最多 5000");
    }

    private static void positive(String key, Double v, boolean allowZero) {
        if (v == null) return;
        if (!Double.isFinite(v) || v < 0 || (!allowZero && v == 0)) throw new IllegalArgumentException(key + " 必须是正数");
    }

    /** 从配置或 JSON 读取；不认识的键直接报错（防止拼错后静默变成"不改"）。 */
    public static VariantOverrides parse(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) return NONE;
        Double rel = null, depth = null, trade = null, btc = null, cMin = null, cMax = null, vol = null, h = null, mfe = null, loss = null;
        HotEntry hot = null;
        for (var e : raw.entrySet()) {
            String k = e.getKey().trim().toLowerCase(Locale.ROOT).replace('_', '-');
            String v = String.valueOf(e.getValue()).trim();
            switch (k) {
                case "min-buy-rel1h" -> rel = num(k, v);
                case "min-depth-usdt" -> depth = num(k, v);
                case "trade-usdt" -> trade = num(k, v);
                case "btc24h-above-pct" -> btc = num(k, v);
                case "coin24h-band-pct" -> {
                    int dash = v.indexOf('-', 1);
                    if (dash < 0) throw new IllegalArgumentException("coin24h-band-pct 要写成 \"低-高\"，例如 5-10");
                    cMin = num(k, v.substring(0, dash));
                    cMax = num(k, v.substring(dash + 1));
                }
                case "hot-entry" -> hot = HotEntry.valueOf(v.toUpperCase(Locale.ROOT));
                case "max-volume-accel" -> vol = num(k, v);
                case "early-exit-hours" -> h = num(k, v);
                case "early-exit-max-mfe-pct" -> mfe = num(k, v);
                case "early-exit-loss-pct" -> loss = num(k, v);
                default -> throw new IllegalArgumentException("不认识的变体参数 " + e.getKey());
            }
        }
        if (hot != null && btc == null && cMin == null) throw new IllegalArgumentException("hot-entry 需要 btc24h-above-pct 或 coin24h-band-pct");
        return new VariantOverrides(rel, depth, trade, btc, cMin, cMax, hot, vol, h, mfe, loss);
    }

    /** 命令行写法 "key=v,key=v"（回测入口和 candidates.json 用）。 */
    public static VariantOverrides parseInline(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s != null && !s.isBlank()) {
            for (String kv : s.split(",")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("看不懂的变体参数 " + kv);
                m.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return parse(m);
    }

    private static Double num(String k, String v) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(k + " 不是数字：" + v);
        }
    }

    public boolean isEmpty() {
        return equals(NONE);
    }

    /** 规范写法（键排序），用于存库、比较是否同一组参数。 */
    public Map<String, String> toMap() {
        Map<String, String> m = new TreeMap<>();
        put(m, "min-buy-rel1h", minBuyRel1h);
        put(m, "min-depth-usdt", minDepthUsdt);
        put(m, "trade-usdt", tradeUsdt);
        put(m, "btc24h-above-pct", btc24hAbovePct);
        if (coin24hMinPct != null) m.put("coin24h-band-pct", fmt(coin24hMinPct) + "-" + fmt(coin24hMaxPct));
        if (hotEntry != null || btc24hAbovePct != null || coin24hMinPct != null) {
            m.put("hot-entry", (hotEntry == null ? HotEntry.SKIP : hotEntry).name());
        }
        put(m, "max-volume-accel", maxVolumeAccel);
        put(m, "early-exit-hours", earlyExitHours);
        put(m, "early-exit-max-mfe-pct", earlyExitMaxMfePct);
        put(m, "early-exit-loss-pct", earlyExitLossPct);
        return m;
    }

    public String inline() {
        return String.join(",", toMap().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
    }

    private static void put(Map<String, String> m, String k, Double v) {
        if (v != null) m.put(k, fmt(v));
    }

    static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** 在 base 之上叠加 top（top 里设置了的键覆盖 base）。 */
    public static VariantOverrides merge(VariantOverrides base, VariantOverrides top) {
        Map<String, String> m = new TreeMap<>(base.toMap());
        Map<String, String> t = top.toMap();
        if (t.containsKey("btc24h-above-pct") || t.containsKey("coin24h-band-pct")) {
            m.remove("btc24h-above-pct");
            m.remove("coin24h-band-pct");
        }
        m.putAll(t);
        return parse(m);
    }

    /** 是否改了买入端（审核或开仓过滤）。 */
    public boolean hasEntryRules() {
        return btc24hAbovePct != null || coin24hMinPct != null || maxVolumeAccel != null;
    }

    public boolean hasEarlyExit() {
        return earlyExitHours != null;
    }

    public double minBuyRel1hOr(double dflt) {
        return minBuyRel1h == null ? dflt : minBuyRel1h;
    }

    public double minDepthUsdtOr(double dflt) {
        return minDepthUsdt == null ? dflt : minDepthUsdt;
    }

    public double tradeUsdtOr(double dflt) {
        return tradeUsdt == null ? dflt : tradeUsdt;
    }

    /**
     * 新开仓的金额倍数：1 = 正常，0.5 = 半仓（H1 HALF），0 = 不开仓。加仓不经过这里。
     *
     * @param btc24hPct   BTC 24h 涨跌（%）
     * @param coin24hPct  这个币 24h 涨跌（%）
     * @param volumeAccel 放量倍数（EARLY 信号里的 volumeAccel）
     */
    public double entryMultiplier(double btc24hPct, double coin24hPct, double volumeAccel) {
        if (maxVolumeAccel != null && !(volumeAccel <= maxVolumeAccel)) return 0;
        boolean hot = false;
        if (btc24hAbovePct != null) {
            if (Double.isNaN(btc24hPct)) return 0;
            hot = btc24hPct > btc24hAbovePct;
        }
        if (coin24hMinPct != null) {
            if (Double.isNaN(coin24hPct)) return 0;
            hot |= coin24hPct >= coin24hMinPct && coin24hPct <= coin24hMaxPct;
        }
        if (!hot) return 1;
        return hotEntry == HotEntry.HALF ? 0.5 : 0;
    }

    /**
     * H2 提前离场（每根新收盘的 1h K 线判断一次）。
     *
     * @param held        已持有时长
     * @param maxNetUsdt  持仓期间最大可退出净利（U）
     * @param cost        本金（U）
     * @param hourRetPct  最近一根已收盘 1h K 线收盘价相对均价的涨跌（%）
     */
    public boolean earlyExit(Duration held, boolean protectArmed, double maxNetUsdt, double cost, double hourRetPct) {
        if (!hasEarlyExit() || protectArmed || !(cost > 0) || Double.isNaN(maxNetUsdt) || Double.isNaN(hourRetPct)) return false;
        if (held.toMinutes() < Math.round(earlyExitHours * 60)) return false;
        return maxNetUsdt < cost * earlyExitMaxMfePct / 100 && hourRetPct < -earlyExitLossPct;
    }

    /** 中文简述（日报、Telegram 用）。 */
    public String describe() {
        if (isEmpty()) return "与 V2 相同";
        java.util.List<String> p = new java.util.ArrayList<>();
        if (minBuyRel1h != null) p.add("新开仓 1h 相对 BTC ≥ " + fmt(minBuyRel1h) + "%");
        if (minDepthUsdt != null) p.add("深度门槛 " + fmt(minDepthUsdt) + " U");
        if (tradeUsdt != null) p.add("每笔 " + fmt(tradeUsdt) + " U");
        String act = hotEntry == HotEntry.HALF ? "半仓" : "不开仓";
        if (btc24hAbovePct != null) p.add("BTC 24h > " + fmt(btc24hAbovePct) + "% 时" + act);
        if (coin24hMinPct != null) p.add("币 24h 涨 " + fmt(coin24hMinPct) + "%～" + fmt(coin24hMaxPct) + "% 时" + act);
        if (maxVolumeAccel != null) p.add("放量 > " + fmt(maxVolumeAccel) + " 倍不买");
        if (earlyExitHours != null) {
            p.add("持有 " + fmt(earlyExitHours) + "h 最大浮盈 < " + fmt(earlyExitMaxMfePct) + "% 且亏 > " + fmt(earlyExitLossPct) + "% 离场");
        }
        return String.join("；", p);
    }
}
