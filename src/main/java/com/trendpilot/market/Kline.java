package com.trendpilot.market;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 一根 K 线。价格与成交额用 double。 */
public record Kline(long openTime, double open, double high, double low, double close,
                    double quoteVolume, long closeTime) {

    private static final Logger log = LoggerFactory.getLogger(Kline.class);

    /** (最高 − 最低) / 开盘，百分比。 */
    public double rangePct() {
        return open > 0 ? (high - low) / open * 100 : 0;
    }

    /**
     * 价格是否可信：全部有限，0 &lt; 最低 ≤ min(开, 收)，max(开, 收) ≤ 最高，成交额有限且不为负。
     * 不满足的（交易所消息缺字段时会解析成 0）一律按缺失处理：不入库、不进任何规则。
     */
    public boolean valid() {
        return Double.isFinite(open) && Double.isFinite(high) && Double.isFinite(low) && Double.isFinite(close)
                && Double.isFinite(quoteVolume) && quoteVolume >= 0
                && low > 0 && low <= Math.min(open, close) && Math.max(open, close) <= high;
    }

    /** 去掉价格不可信的 K 线（记 warn），其余原样保留。 */
    public static List<Kline> validOnly(List<Kline> bars, String what) {
        List<Kline> out = null;
        for (int i = 0; i < bars.size(); i++) {
            Kline k = bars.get(i);
            if (k.valid()) {
                if (out != null) out.add(k);
                continue;
            }
            if (out == null) out = new ArrayList<>(bars.subList(0, i));
            log.warn("{}：{} 的 K 线价格不合理（开 {} 高 {} 低 {} 收 {}），按缺失处理", what,
                    java.time.Instant.ofEpochMilli(k.openTime()), k.open(), k.high(), k.low(), k.close());
        }
        return out == null ? bars : out;
    }
}
