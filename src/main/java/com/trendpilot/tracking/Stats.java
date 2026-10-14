package com.trendpilot.tracking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/** 统计工具：分位数、截尾均值、Kaplan–Meier、按日分块 bootstrap。 */
public final class Stats {

    private Stats() {
    }

    /** 线性插值分位数，q ∈ [0,1]；空列表为 NaN。 */
    public static double quantile(List<Double> values, double q) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> v = values.stream().sorted().toList();
        double pos = q * (v.size() - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return v.get(lo) + (v.get(hi) - v.get(lo)) * (pos - lo);
    }

    /** 去掉两端各 5% 的均值。 */
    public static double trimmedMean(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> v = values.stream().sorted().toList();
        int cut = (int) Math.floor(v.size() * 0.05);
        return v.subList(cut, v.size() - cut).stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    /** 一个样本的事件时间：发生为 time，未发生为在 observed 处删失。 */
    public record Survival(double time, boolean event) {
    }

    /** Kaplan–Meier 曲线上的累计达成比例：返回 [时间, 1 − S(t)] 的列表。 */
    public static List<double[]> kaplanMeier(List<Survival> data) {
        List<Survival> sorted = new ArrayList<>(data);
        sorted.sort(Comparator.comparingDouble(Survival::time).thenComparing(s -> !s.event()));
        List<double[]> curve = new ArrayList<>();
        double s = 1;
        int atRisk = sorted.size();
        int i = 0;
        while (i < sorted.size()) {
            double t = sorted.get(i).time();
            int events = 0, leaving = 0;
            while (i < sorted.size() && sorted.get(i).time() == t) {
                if (sorted.get(i).event()) events++;
                leaving++;
                i++;
            }
            if (events > 0 && atRisk > 0) {
                s *= 1 - events / (double) atRisk;
                curve.add(new double[]{t, 1 - s});
            }
            atRisk -= leaving;
        }
        return curve;
    }

    /** 达成比例首次 ≥ q 的时间；达不到为 NaN（说明窗口内多数样本没发生）。 */
    public static double kmQuantile(List<Survival> data, double q) {
        for (double[] p : kaplanMeier(data)) {
            if (p[1] >= q - 1e-12) return p[0];
        }
        return Double.NaN;
    }

    /** t 时刻的累计达成比例。 */
    public static double kmShareBy(List<Survival> data, double t) {
        double share = 0;
        for (double[] p : kaplanMeier(data)) {
            if (p[0] > t) break;
            share = p[1];
        }
        return share;
    }

    /** 按自然日分块 bootstrap 的 90% 区间：整天的样本一起重抽。 */
    public static <T> double[] dayBlockBootstrap(List<T> samples, Function<T, String> day,
                                                 Function<List<T>, Double> statistic, int iterations, long seed) {
        Map<String, List<T>> byDay = new LinkedHashMap<>();
        samples.forEach(s -> byDay.computeIfAbsent(day.apply(s), k -> new ArrayList<>()).add(s));
        List<List<T>> blocks = new ArrayList<>(byDay.values());
        if (blocks.isEmpty()) return new double[]{Double.NaN, Double.NaN};
        Random rnd = new Random(seed);
        List<Double> stats = new ArrayList<>();
        for (int it = 0; it < iterations; it++) {
            List<T> resample = new ArrayList<>();
            for (int b = 0; b < blocks.size(); b++) resample.addAll(blocks.get(rnd.nextInt(blocks.size())));
            Double v = statistic.apply(resample);
            if (v != null && !v.isNaN()) stats.add(v);
        }
        return new double[]{quantile(stats, 0.05), quantile(stats, 0.95)};
    }
}
