package com.trendpilot.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.BarsLoader;
import com.trendpilot.market.Kline;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;

/**
 * 测试用行情构造器。所有行情都先生成 15m K 线序列（与 BarsLoader 的缓存窗口对齐），
 * 1h K 线由 BarsLoader.toHourly 合成，和生产路径一致。
 */
public final class TestData {

    /**
     * 测试数据库目录：放在 target/test-data 下，由 mvn clean 清理。
     * 不用 @TempDir：Windows 上 Spring 上下文缓存期间 SQLite 文件被占用，JUnit 删不掉会报错。
     */
    public static Path dataDir() {
        try {
            return Files.createDirectories(Path.of("target", "test-data", java.util.UUID.randomUUID().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    public static final long Q = BarsLoader.QUARTER_MS;

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

    /**
     * 生成截面 now 之前的 15m 序列（共 BarsLoader.WINDOW 根，最后一根是最新收盘的那根）。
     * hours[i] = {open, close, rangePct} 描述最近 24 个完整小时（最后一个是刚结束的小时），
     * 每小时的 4 根 15m 从 open 线性走到 close，第一根带上整小时的波幅。
     * 窗口里早于这 24 小时、以及当前未完成小时的 15m 线，都按 tailPrice 横盘（当前小时从 lastClose 到 tailClose）。
     */
    public static List<Kline> quarters(Instant now, double[][] hours, double tailClose, double[] lastFourVolumes,
                                       double baseVolume) {
        long lastOpen = BarsLoader.lastClosedOpenTime(now);
        long first = lastOpen - (BarsLoader.WINDOW - 1) * Q;
        long currentHour = Math.floorDiv(lastOpen, BarsLoader.HOUR_MS) * BarsLoader.HOUR_MS;
        long firstFullHour = currentHour - 24 * BarsLoader.HOUR_MS;
        List<Kline> out = new ArrayList<>();
        for (long t = first; t <= lastOpen; t += Q) {
            double o, c, range;
            if (t < firstFullHour) {
                o = c = hours[0][0];
                range = 0.1;
            } else if (t >= currentHour) {
                o = hours[23][1];
                c = tailClose;
                range = 0.1;
            } else {
                int h = (int) ((t - firstFullHour) / BarsLoader.HOUR_MS);
                int k = (int) ((t - firstFullHour) % BarsLoader.HOUR_MS / Q);
                double ho = hours[h][0], hc = hours[h][1];
                o = ho + (hc - ho) * k / 4;
                c = ho + (hc - ho) * (k + 1) / 4;
                range = k == 0 ? hours[h][2] : 0.05;
            }
            double mid = (o + c) / 2;
            double hi = Math.max(Math.max(o, c), mid * (1 + range / 200));
            double lo = Math.min(Math.min(o, c), mid * (1 - range / 200));
            out.add(new Kline(t, o, hi, lo, c, baseVolume, t + Q - 1));
        }
        if (lastFourVolumes != null) {
            for (int i = 0; i < 4; i++) {
                Kline k = out.get(out.size() - 4 + i);
                out.set(out.size() - 4 + i, new Kline(k.openTime(), k.open(), k.high(), k.low(), k.close(),
                        lastFourVolumes[i], k.closeTime()));
            }
        }
        return out;
    }

    public static List<Kline> flatQuarters(Instant now, double price) {
        double[][] h = new double[24][];
        for (int i = 0; i < 24; i++) h[i] = new double[]{price, price, 0.5};
        return quarters(now, h, price, null, 250_000);
    }

    /** 一个满足全部 EARLY 条件、评分 10 的强势标的（BTC 平盘时），最新价 104。 */
    public static List<Kline> strongQuarters(Instant now) {
        double[][] h = new double[24][];
        for (int i = 0; i < 18; i++) h[i] = new double[]{100, 100, 3.0};
        h[18] = new double[]{100, 100, 0.2};
        h[19] = new double[]{100, 100.3, 0.5};
        h[20] = new double[]{100.3, 100.6, 0.5};
        h[21] = new double[]{100.6, 101, 0.6};
        h[22] = new double[]{101, 102.5, 1.6};
        h[23] = new double[]{102.5, 104, 1.6};
        return quarters(now, h, 104.2, new double[]{2000, 2000, 2000, 2000}, 1000);
    }

    public static SymbolBars bars(String symbol, List<Kline> quarters) {
        List<Kline> hourly = BarsLoader.toHourly(quarters);
        return new SymbolBars(symbol, hourly.subList(Math.max(0, hourly.size() - 26), hourly.size()),
                quarters.subList(quarters.size() - 22, quarters.size()));
    }

    public static SymbolBars flat(String symbol, Instant now, double price) {
        return bars(symbol, flatQuarters(now, price));
    }

    public static SymbolBars strong(String symbol, Instant now) {
        return bars(symbol, strongQuarters(now));
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
