package com.trendpilot.signal;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.Kline;
import com.trendpilot.market.SymbolBars;

/**
 * EARLY 信号。六个条件：
 * <ol>
 * <li>REL_1H：最近 2 根小时 K 线（首开至末收）相对 BTC 收益 ≥ rel1h</li>
 * <li>REL_4H：最近 5 根小时 K 线相对 BTC 收益 ≥ rel4h</li>
 * <li>ACCEL：本段 1h 相对收益 − 前一段 1h 相对收益 ≥ accel</li>
 * <li>VOLUME：最近 4 根 15m 成交额 / 之前 16 根每 4 根平均成交额 ≥ volumeAccel</li>
 * <li>COMPRESSION：之前小时 K 线平均波幅 / 最近 6 根平均波幅 ≥ compression</li>
 * <li>MOMENTUM：最近一根 15m 收益 ≥ 0 且 1h 相对收益 ≥ momentumRel1h</li>
 * </ol>
 * 评分满分 10：相对强度 5、加速度 2、量能 2、压缩 1。
 */
@Component
public class EarlySignalCalculator {

    public static final int MIN_HOURLY = 24;
    public static final int MIN_QUARTER = 20;

    private final StrategyParams.Early p;

    public EarlySignalCalculator(StrategyParams params) {
        this.p = params.early();
    }

    /** 数据不够时返回 null（记为缺数据，而不是"没信号"）。 */
    public EarlySignal compute(SymbolBars bars, SymbolBars btc) {
        if (!enough(bars) || !enough(btc)) {
            return null;
        }
        List<Kline> h = bars.hourly();
        List<Kline> bh = btc.hourly();
        double rel1h = ret(h, h.size() - 2, h.size()) - ret(bh, bh.size() - 2, bh.size());
        double rel4h = ret(h, h.size() - 5, h.size()) - ret(bh, bh.size() - 5, bh.size());
        double prevRel1h = ret(h, h.size() - 4, h.size() - 2) - ret(bh, bh.size() - 4, bh.size() - 2);
        double accel = rel1h - prevRel1h;

        List<Kline> q = bars.quarter();
        double recentVol = sumQuoteVolume(q, q.size() - 4, q.size());
        double baseVol = sumQuoteVolume(q, q.size() - 20, q.size() - 4) / 4;
        double volumeAccel = baseVol > 0 ? recentVol / baseVol : 0;

        double recentRange = avgRange(h, h.size() - 6, h.size());
        double baseRange = avgRange(h, h.size() - 24, h.size() - 6);
        double compression = recentRange > 0 ? baseRange / recentRange : 0;

        double ret15m = ret(q, q.size() - 1, q.size());
        double atr = avgRange(h, h.size() - 14, h.size());

        List<String> hits = new ArrayList<>();
        if (rel1h >= p.rel1h()) hits.add("REL_1H");
        if (rel4h >= p.rel4h()) hits.add("REL_4H");
        if (accel >= p.accel()) hits.add("ACCEL");
        if (volumeAccel >= p.volumeAccel()) hits.add("VOLUME");
        if (compression >= p.compression()) hits.add("COMPRESSION");
        if (ret15m >= 0 && rel1h >= p.momentumRel1h()) hits.add("MOMENTUM");

        double score = clamp(rel1h / p.rel1h(), 0, 2) * 1.5
                + clamp(rel4h / p.rel4h(), 0, 2) * 1.0
                + clamp(accel / p.accel(), 0, 2) * 1.0
                + clamp((volumeAccel - 1) / (p.volumeAccel() - 1), 0, 2) * 1.0
                + clamp((compression - 1) / (p.compression() - 1), 0, 1) * 1.0;

        return new EarlySignal(bars.symbol(), h.getLast().close(), rel1h, rel4h, accel, volumeAccel, compression,
                ret15m, atr, List.copyOf(hits), hits.size() >= p.minHits(), round2(score));
    }

    /**
     * 命中条件里窗口完全在 since 之后开始的那些（用于再入场：信号不能全靠卖出前的涨幅）。
     * REL_1H / MOMENTUM 看最近 2 根小时 K 线，VOLUME 看最近 4 根 15m K 线；其余条件窗口更长，不算。
     */
    public static List<String> hitsAfter(SymbolBars bars, EarlySignal s, java.time.Instant since) {
        if (bars == null || s == null || bars.hourly().size() < 2 || bars.quarter().size() < 4) return List.of();
        long t = since.toEpochMilli();
        List<Kline> h = bars.hourly();
        List<Kline> q = bars.quarter();
        boolean hourWindow = h.get(h.size() - 2).openTime() >= t;
        boolean quarterWindow = q.get(q.size() - 4).openTime() >= t;
        List<String> out = new ArrayList<>();
        for (String hit : s.hits()) {
            if ((hit.equals("REL_1H") || hit.equals("MOMENTUM")) && hourWindow) out.add(hit);
            if (hit.equals("VOLUME") && quarterWindow) out.add(hit);
        }
        return out;
    }

    private static boolean enough(SymbolBars b) {
        return b != null && b.hourly().size() >= MIN_HOURLY && b.quarter().size() >= MIN_QUARTER;
    }

    /** bars[from, to) 首根开盘到末根收盘的收益，百分比。 */
    static double ret(List<Kline> bars, int from, int to) {
        double open = bars.get(from).open();
        return open > 0 ? (bars.get(to - 1).close() / open - 1) * 100 : 0;
    }

    private static double sumQuoteVolume(List<Kline> bars, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += bars.get(i).quoteVolume();
        return s;
    }

    private static double avgRange(List<Kline> bars, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += bars.get(i).rangePct();
        return s / (to - from);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
