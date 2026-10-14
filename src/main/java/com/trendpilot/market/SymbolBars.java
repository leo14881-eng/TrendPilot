package com.trendpilot.market;

import java.util.List;

/** 某个标的在一个时间截面上的已收盘 K 线。 */
public record SymbolBars(String symbol, List<Kline> hourly, List<Kline> quarter) {
}
