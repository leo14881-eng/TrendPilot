package com.trendpilot.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.Kline;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;

/** 测试用行情构造器。 */
public final class TestData {

    public static final long HOUR = 3_600_000L;
    public static final long QUARTER = 900_000L;

    private TestData() {
    }

    /** 读取 application.yml 中的真实策略参数。 */
    public static StrategyParams params() {
        try {
            var env = new StandardEnvironment();
            new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
                    .forEach(ps -> env.getPropertySources().addLast(ps));
            return Binder.get(env).bind("trendpilot.strategy", StrategyParams.class).get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 由 (open, close, rangePct) 生成已收盘 K 线，最后一根在 now 前 1 分钟收盘。 */
    public static List<Kline> bars(Instant now, long interval, double[][] ocr, double quoteVolume) {
        long lastClose = now.toEpochMilli() - 60_000;
        int n = ocr.length;
        List<Kline> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long close = lastClose - (long) (n - 1 - i) * interval;
            double o = ocr[i][0], c = ocr[i][1], range = ocr[i][2];
            double mid = (o + c) / 2;
            double hi = Math.max(Math.max(o, c), mid * (1 + range / 200));
            double lo = Math.min(Math.min(o, c), mid * (1 - range / 200));
            out.add(new Kline(close - interval + 1, o, hi, lo, c, quoteVolume, close));
        }
        return out;
    }

    public static List<Kline> withVolumes(List<Kline> bars, double[] volumes) {
        List<Kline> out = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            Kline k = bars.get(i);
            out.add(new Kline(k.openTime(), k.open(), k.high(), k.low(), k.close(), volumes[i], k.closeTime()));
        }
        return out;
    }

    public static SymbolBars flat(String symbol, Instant now, double price) {
        double[][] h = new double[24][];
        for (int i = 0; i < 24; i++) h[i] = new double[]{price, price, 0.5};
        double[][] q = new double[20][];
        for (int i = 0; i < 20; i++) q[i] = new double[]{price, price, 0.2};
        return new SymbolBars(symbol, bars(now, HOUR, h, 1_000_000), bars(now, QUARTER, q, 250_000));
    }

    /** 一个满足全部 EARLY 条件、评分 10 的强势标的（BTC 平盘时）。 */
    public static SymbolBars strong(String symbol, Instant now) {
        double[][] h = new double[24][];
        for (int i = 0; i < 18; i++) h[i] = new double[]{100, 100, 3.0};
        h[18] = new double[]{100, 100, 0.2};
        h[19] = new double[]{100, 100.3, 0.5};
        h[20] = new double[]{100.3, 100.6, 0.5};
        h[21] = new double[]{100.6, 101, 0.6};
        h[22] = new double[]{101, 102.5, 1.6};
        h[23] = new double[]{102.5, 104, 1.6};
        double[][] q = new double[20][];
        for (int i = 0; i < 19; i++) q[i] = new double[]{103.5, 103.5, 0.2};
        q[19] = new double[]{103.5, 104, 0.6};
        double[] vol = new double[20];
        for (int i = 0; i < 20; i++) vol[i] = i < 16 ? 1000 : 2000;
        return new SymbolBars(symbol, bars(now, HOUR, h, 1_000_000), withVolumes(bars(now, QUARTER, q, 0), vol));
    }

    /** 以 mid 为中心、单边 20 档、每档间隔 0.05%、每档 qty 的盘口。 */
    public static OrderBook book(String symbol, double mid, double halfSpreadBps, double qty, Instant at) {
        List<OrderBook.Level> bids = new ArrayList<>();
        List<OrderBook.Level> asks = new ArrayList<>();
        double bid = mid * (1 - halfSpreadBps / 10_000);
        double ask = mid * (1 + halfSpreadBps / 10_000);
        for (int i = 0; i < 20; i++) {
            bids.add(new OrderBook.Level(bd(bid * (1 - 0.0005 * i)), bd(qty)));
            asks.add(new OrderBook.Level(bd(ask * (1 + 0.0005 * i)), bd(qty)));
        }
        return new OrderBook(symbol, bids, asks, at);
    }

    public static BigDecimal bd(double v) {
        return new BigDecimal(String.format(java.util.Locale.ROOT, "%.8f", v));
    }

}
