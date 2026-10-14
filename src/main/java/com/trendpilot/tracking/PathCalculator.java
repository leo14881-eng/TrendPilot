package com.trendpilot.tracking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 信号后的路径指标（设计文档第 3、4 节），纯函数：输入起点与成本口径、15m K 线副本，输出指标。
 * <ul>
 * <li>净收益 r_net(P) = P / P0 × (1 − s/2) × (1 − f) × (1 − i) − 1 − f：扣双边手续费、退出半个点差和退出冲击。</li>
 * <li>包含 t0 的那根 K 线只用收盘价（它的高低点可能发生在 t0 之前）。</li>
 * <li>同一根 K 线内：算"高点前的回撤"时低点算在高点之前；算"高点后的回撤"只用之后 K 线的低点。</li>
 * <li>时间单位为分钟，从 t0 起算到 K 线收盘；未发生为 null（删失，见 observedMinutes）。</li>
 * </ul>
 */
public final class PathCalculator {

    public static final String VERSION = "c1";
    static final long Q = 15 * 60_000L;

    private PathCalculator() {
    }

    public record Bar(long closeTime, double open, double high, double low, double close) {
    }

    /** 起点价与成本口径。 */
    public record Cost(double p0, double feeRate, double spreadFrac, double impactFrac) {
        public double net(double price) {
            return (price / p0 * (1 - spreadFrac / 2) * (1 - feeRate) * (1 - impactFrac) - 1 - feeRate) * 100;
        }

        public double raw(double price) {
            return (price / p0 - 1) * 100;
        }

        /** 净收益百分比 r 对应的价格（net 的反函数）。 */
        public double priceAtNet(double r) {
            return (r / 100 + 1 + feeRate) * p0 / ((1 - spreadFrac / 2) * (1 - feeRate) * (1 - impactFrac));
        }
    }

    public record Pullback(int seq, long peakTime, double peakGainNet, long troughTime, double depthPct,
                           boolean recovered, Long recoverTime) {
    }

    public record Result(Map<String, Object> metrics, List<Pullback> pullbacks, int observedMinutes, int gapBars,
                         int ambiguousBars, boolean dataGap) {
        public Integer minutes(String key) {
            Object v = metrics.get(key);
            return v == null ? null : ((Number) v).intValue();
        }

        public Double value(String key) {
            Object v = metrics.get(key);
            return v == null ? null : ((Number) v).doubleValue();
        }
    }

    public static Result compute(long t0, Cost c, double atrPct, List<Bar> input, boolean delisted,
                                 TrackingProperties p) {
        long extEnd = t0 + p.extWindowHours() * 3_600_000L;
        long mainEnd = t0 + p.mainWindowHours() * 3_600_000L;
        // 1. 只取 t0 之后收盘、窗口内的 K 线；检查缺口
        List<Bar> bars = new ArrayList<>();
        int gaps = 0;
        boolean dataGap = false;
        long prev = -1;
        for (Bar b : input) {
            if (b.closeTime() <= t0 || b.closeTime() > extEnd) continue;
            if (prev > 0) {
                int missing = (int) ((b.closeTime() - prev) / Q) - 1;
                if (missing > 0) {
                    double ratio = (gaps + missing) / (double) ((b.closeTime() - t0) / Q);
                    if (missing > p.maxGapRun() || ratio > p.maxGapRatio()) {
                        dataGap = true;
                        break; // 缺口之后的路径置空，按删失处理
                    }
                    gaps += missing;
                }
            }
            bars.add(b);
            prev = b.closeTime();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        List<Pullback> pullbacks = new ArrayList<>();
        if (bars.isEmpty()) {
            return new Result(m, pullbacks, 0, gaps, 0, dataGap);
        }
        long lastClose = bars.getLast().closeTime();
        int observed = delisted && !dataGap ? (int) ((extEnd - t0) / 60_000) : (int) ((lastClose - t0) / 60_000);

        Integer tProfit = null, tTouch = null, tArm = null;
        Map<Double, Integer> tBig = new LinkedHashMap<>();
        p.bigThresholdsPct().forEach(k -> tBig.put(k, null));
        Integer tAtr3 = null, tAtr6 = null;
        double mfe24 = Double.NEGATIVE_INFINITY, mfe72 = Double.NEGATIVE_INFINITY, mfe7d = Double.NEGATIVE_INFINITY;
        int tMax24 = 0, tMax72 = 0, tMax7d = 0;
        double mae72 = Double.POSITIVE_INFINITY, mae7d = Double.POSITIVE_INFINITY;
        int tMae = 0;
        double maeBeforeProfit = Double.POSITIVE_INFINITY;
        double minLowSoFar = Double.POSITIVE_INFINITY, maeBeforePeak = 0;
        double runMax = Double.NEGATIVE_INFINITY, mddFromPeak72 = 0;
        int profitBars = 0, barsAfterProfit = 0;
        int ambiguous = 0;
        // 跌破 −L 之后能否回到盈亏平衡
        double[] lossLevels = {5, 8, 10, 15, 20, 25};
        Map<Double, Boolean> lossRecovered = new LinkedHashMap<>();
        // 回撤事件
        double pbMax = Double.NEGATIVE_INFINITY;
        long pbMaxTime = 0;
        Pullback open = null;
        double troughLow = 0;
        long troughTime = 0;
        // 波段高点（每个回撤档位一套）
        Map<Double, double[]> swing = new LinkedHashMap<>(); // {runMax, runMaxTime, confirmed(0/1), peak, peakTime, confirmTime, minAfter, recovered, recoverTime}
        p.swingDrawdownsPct().forEach(d -> swing.put(d, new double[]{Double.NEGATIVE_INFINITY, 0, 0, 0, 0, 0,
                Double.POSITIVE_INFINITY, 0, -1}));

        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            boolean partial = b.closeTime() - Q < t0; // 包含 t0 的那根：只用收盘价
            double hi = partial ? b.close() : b.high();
            double lo = partial ? b.close() : b.low();
            int min = (int) ((b.closeTime() - t0) / 60_000);
            boolean inMain = b.closeTime() <= mainEnd;
            double rC = c.net(b.close()), rH = c.net(hi), rL = c.net(lo);

            // 盈利时间
            if (tTouch == null && rH >= 0) tTouch = min;
            if (tArm == null && c.raw(b.close()) >= 2 && rC > 0) tArm = min;
            if (tProfit == null) maeBeforeProfit = Math.min(maeBeforeProfit, rL);
            if (tProfit == null && rC >= 0) tProfit = min;
            if (tProfit != null && inMain) {
                barsAfterProfit++;
                if (rC >= 0) profitBars++;
            }
            for (var e : tBig.entrySet()) {
                if (e.getValue() == null && rC >= e.getKey()) e.setValue(min);
            }
            if (!Double.isNaN(atrPct) && atrPct > 0) {
                if (tAtr3 == null && c.raw(b.close()) >= 3 * atrPct) tAtr3 = min;
                if (tAtr6 == null && c.raw(b.close()) >= 6 * atrPct) tAtr6 = min;
            }
            for (double l : lossLevels) {
                if (!lossRecovered.containsKey(l) && rL <= -l) lossRecovered.put(l, false);
                else if (Boolean.FALSE.equals(lossRecovered.get(l)) && rC >= 0) lossRecovered.put(l, true);
            }

            // 窗口最高点与回撤（相对买入价）
            minLowSoFar = Math.min(minLowSoFar, rL); // 同一根内低点算在高点之前
            if (b.closeTime() <= t0 + 24 * 3_600_000L && rH > mfe24) {
                mfe24 = rH;
                tMax24 = min;
            }
            if (inMain && rH > mfe72) {
                mfe72 = rH;
                tMax72 = min;
                maeBeforePeak = minLowSoFar;
            }
            if (rH > mfe7d) {
                mfe7d = rH;
                tMax7d = min;
            }
            if (inMain && rL < mae72) {
                mae72 = rL;
                tMae = min;
            }
            mae7d = Math.min(mae7d, rL);
            // 相对此前运行最高价的回撤：只用之前 K 线的高点
            if (inMain && runMax > 0) mddFromPeak72 = Math.max(mddFromPeak72, (1 - lo / runMax) * 100);
            if (runMax > 0 && hi > runMax && lo <= hi * (1 - p.pullbackMinPct() / 100)) ambiguous++;

            // 回撤事件（≥ pullbackMinPct），回撤只看高点之后的 K 线
            if (open == null && pbMax > 0 && lo <= pbMax * (1 - p.pullbackMinPct() / 100)) {
                open = new Pullback(pullbacks.size() + 1, pbMaxTime, c.net(pbMax), b.closeTime(), 0, false, null);
                troughLow = lo;
                troughTime = b.closeTime();
            }
            if (open != null) {
                if (hi > pbMax) {
                    pullbacks.add(new Pullback(open.seq(), open.peakTime(), open.peakGainNet(), troughTime,
                            (1 - troughLow / pbMax) * 100, true, b.closeTime()));
                    open = null;
                } else if (lo < troughLow) {
                    troughLow = lo;
                    troughTime = b.closeTime();
                }
            }
            if (hi > pbMax) {
                pbMax = hi;
                pbMaxTime = b.closeTime();
            }

            // 波段高点：确认用此前的运行最高价（低点算在本根高点之前）
            for (var e : swing.entrySet()) {
                double d = e.getKey();
                double[] s = e.getValue();
                if (s[2] == 0) {
                    if (s[0] > 0 && c.raw(s[0]) >= p.swingMinGainPct() && lo <= s[0] * (1 - d / 100)) {
                        s[2] = 1;
                        s[3] = s[0];
                        s[4] = s[1];
                        s[5] = b.closeTime();
                        s[6] = lo;
                    }
                } else if (s[7] == 0) {
                    if (hi > s[3]) {
                        s[7] = 1;
                        s[8] = b.closeTime();
                    } else {
                        s[6] = Math.min(s[6], lo);
                    }
                }
                if (hi > s[0]) {
                    s[0] = hi;
                    s[1] = b.closeTime();
                }
            }
            runMax = Math.max(runMax, hi);
        }
        if (open != null) {
            pullbacks.add(new Pullback(open.seq(), open.peakTime(), open.peakGainNet(), troughTime,
                    (1 - troughLow / pbMax) * 100, false, null));
        }

        m.put("t_profit", tProfit);
        m.put("t_profit_touch", tTouch);
        m.put("t_arm", tArm);
        m.put("profit_hold_ratio", barsAfterProfit == 0 ? null : profitBars / (double) barsAfterProfit);
        tBig.forEach((k, v) -> m.put("t_big_" + k.intValue(), v));
        m.put("t_big_atr3", tAtr3);
        m.put("t_big_atr6", tAtr6);
        m.put("mfe_24h", fin(mfe24));
        m.put("t_max_24h", Double.isFinite(mfe24) ? tMax24 : null);
        m.put("mfe_72h", fin(mfe72));
        m.put("t_max_72h", Double.isFinite(mfe72) ? tMax72 : null);
        m.put("mfe_7d", fin(mfe7d));
        m.put("t_max_7d", tMax7d);
        m.put("mae_72h", fin(mae72));
        m.put("t_mae", Double.isFinite(mae72) ? tMae : null);
        m.put("mae_7d", fin(mae7d));
        m.put("mae_before_profit", fin(maeBeforeProfit));
        m.put("mae_before_peak", Double.isFinite(mfe72) ? maeBeforePeak : null);
        m.put("mdd_from_peak_72h", mddFromPeak72);
        for (var e : swing.entrySet()) {
            String k = "swing_" + e.getKey().intValue() + "_";
            double[] s = e.getValue();
            boolean confirmed = s[2] == 1;
            m.put(k + "time", confirmed ? (int) ((s[4] - t0) / 60_000) : null);
            m.put(k + "confirm_time", confirmed ? (int) ((s[5] - t0) / 60_000) : null);
            m.put(k + "gain", confirmed ? c.net(s[3]) : null);
            m.put(k + "depth", confirmed ? (1 - s[6] / s[3]) * 100 : null);
            m.put(k + "recovered", confirmed ? s[7] == 1 : null);
            m.put(k + "recover_min", confirmed && s[7] == 1 ? (int) ((s[8] - s[4]) / 60_000) : null);
        }
        for (double l : lossLevels) {
            m.put("loss_" + (int) l + "_recovered", lossRecovered.get(l));
        }
        int[] hours = {1, 4, 12, 24, 48, 72, 168};
        for (int h : hours) {
            long target = t0 + h * 3_600_000L;
            Double ret = null;
            for (Bar b : bars) {
                if (b.closeTime() >= target && b.closeTime() < target + Q) {
                    ret = c.net(b.close());
                    break;
                }
            }
            if (ret == null && delisted && !dataGap && target > lastClose) ret = c.net(bars.getLast().close());
            m.put("ret_" + (h == 168 ? "7d" : h + "h"), ret);
        }
        return new Result(m, pullbacks, observed, gaps, ambiguous, dataGap);
    }

    private static Double fin(double v) {
        return Double.isFinite(v) ? v : null;
    }
}
