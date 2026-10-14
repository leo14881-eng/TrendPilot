package com.trendpilot.signal;

import java.util.List;

/**
 * 一个标的在某个截面上的信号指标（收益单位为百分点）。
 * hits 为命中的 EARLY 条件，early() 表示达到最少命中数。
 */
public record EarlySignal(String symbol, double price, double rel1h, double rel4h, double accel,
                          double volumeAccel, double compression, double ret15m, double atr1hPct,
                          List<String> hits, boolean early, double score) {
}
