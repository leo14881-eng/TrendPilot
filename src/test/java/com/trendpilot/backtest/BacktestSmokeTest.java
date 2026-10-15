package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.v2n.V2nParams;

/** 用合成行情把整条回测流程跑一遍：现行 V2 和新规则都能跑完，账目自洽。 */
class BacktestSmokeTest {

    static MarketData synthetic(int symbols, int days, long seed) {
        Random rnd = new Random(seed);
        int size = days * 96;
        long start = 1_756_684_800_000L; // 2025-09-01 UTC
        Map<String, Series> map = new HashMap<>();
        for (int k = 0; k <= symbols; k++) {
            String sym = k == 0 ? MarketData.BTC : "C" + k + "USDT";
            Series s = new Series(sym, start, size);
            double p = k == 0 ? 100_000 : 1 + rnd.nextDouble() * 10;
            double vol = k == 0 ? 0.002 : 0.004 + rnd.nextDouble() * 0.01;
            double drift = 0;
            for (int i = 0; i < size; i++) {
                if (i % 96 == 0) drift = (rnd.nextDouble() - 0.5) * 0.002;
                // 偶尔来一段拉升，让 EARLY 信号出现
                double burst = k > 0 && rnd.nextDouble() < 0.002 ? 0.03 : 0;
                double r = drift + burst + rnd.nextGaussian() * vol;
                double o = p;
                p = Math.max(1e-6, p * (1 + r));
                double hi = Math.max(o, p) * (1 + Math.abs(rnd.nextGaussian()) * vol / 2);
                double lo = Math.min(o, p) * (1 - Math.abs(rnd.nextGaussian()) * vol / 2);
                double qv = (5_000_000 + rnd.nextDouble() * 20_000_000) / 96 * (1 + burst * 50);
                s.set(i, o, hi, lo, p, qv);
            }
            map.put(sym, s);
        }
        return new MarketData(start, size, map, 150, Set.of());
    }

    @Test
    void bothBooksRunAndBalance() throws Exception {
        StrategyParams params = BacktestMain.loadParams();
        MarketData data = synthetic(40, 45, 7);
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        int fromQ = 10 * 96;
        List<Sim> sims = List.of(new B0Sim("B0", data, regimes, tape, params, fromQ, data.size),
                new B0Sim("B0-冻结修复", data, regimes, tape, params, fromQ, data.size, true),
                new NSim(V2nParams.base().with("N-论点失效直接卖", b -> b.set("decayMode", "SELL")), data, regimes, tape, params, fromQ, data.size),
                new NSim(V2nParams.base(), data, regimes, tape, params, fromQ, data.size),
                new NSim(V2nParams.base().with("N-无山寨门", b -> b.set("altGate", false).set("trendGate", false)),
                        data, regimes, tape, params, fromQ, data.size));
        for (Sim s : sims) {
            s.run();
            double sum = s.trades().stream().mapToDouble(Trade::pnl).sum();
            assertThat(s.equity()[data.size / 4]).isCloseTo(sum, org.assertj.core.data.Offset.offset(1e-6));
            Metrics m = Metrics.of(s, regimes, fromQ, data.size);
            assertThat(m.trades()).isEqualTo(s.trades().size());
        }
        assertThat(sims.get(0).trades()).isNotEmpty();
        EdgeStudy edge = new EdgeStudy(data, regimes, tape, params.review().minScore());
        edge.run(fromQ, data.size);
        assertThat(edge.count(EdgeStudy.ALL, 0)).isGreaterThan(0);
        assertThat(edge.count(EdgeStudy.ALL, 0)).isGreaterThanOrEqualTo(edge.count(0, 0) + edge.count(3, 0) + edge.count(4, 0));
        FactorStudy factors = new FactorStudy(data, 30);
        factors.run(fromQ, data.size);
        assertThat(factors.periods("7 天涨幅（动量）", 0)).isGreaterThan(0);
        assertThat(factors.markdown()).contains("因子检验").contains("只持有 BTC");
        PumpStudy pump = new PumpStudy(data);
        pump.run(fromQ, data.size);
        assertThat(pump.markdown()).contains("追大涨检验");
        PrePumpStudy pre = new PrePumpStudy(data);
        pre.run(fromQ, data.size);
        assertThat(pre.markdown()).contains("大涨前埋伏检验").contains("合并抓到率");
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("bt");
        pre.writeCsv(dir);
        pump.writeCsv(dir.resolve("pump-trades.csv.gz"));
        assertThat(dir.resolve("prepump-trades.csv.gz")).exists();
        assertThat(dir.resolve("prepump-events.csv.gz")).exists();
        TrendStudy trend = new TrendStudy(data);
        trend.run(fromQ, fromQ + 15 * 96, data.size);
        assertThat(trend.results()).isNotEmpty();
        assertThat(trend.results().get(0).returnPct()).isNotNaN();
        trend.writeBtcCsv(dir.resolve("trend-btc-daily.csv"));
        assertThat(java.nio.file.Files.readAllLines(dir.resolve("trend-btc-daily.csv")).get(0)).contains("nav_ma50");
        Nav nav = new Nav(data, params.capital().hardCap());
        double[] curve = nav.curve(sims.get(0), fromQ / 4, data.size / 4);
        assertThat(curve[0]).isEqualTo(1.0);
        Report report = new Report(regimes, fromQ, fromQ + 15 * 96, data.size, "合成数据").with(data, nav, edge.markdown());
        sims.forEach(s -> report.add(new Report.Run(s, s instanceof NSim ? V2nParams.base() : null, "x")));
        String md = report.build(List.of("N"), List.of(), List.of());
        assertThat(md).contains("PASSED=").contains("净值曲线对照").contains("信号本身有没有优势").contains("卖出后的走势");
    }

    @Test
    void oldProtectionOverrideBinds() throws Exception {
        StrategyParams old = BacktestMain.loadParams(java.util.Map.of("trendpilot.strategy.exit.protect-giveback-pct", 2,
                "trendpilot.strategy.exit.protect-lock-ratio", 0));
        assertThat(old.exit().protectGivebackPct()).isEqualTo(2.0);
        assertThat(old.exit().protectLockRatio()).isEqualTo(0.0);
        assertThat(BacktestMain.loadParams().exit().protectLockRatio()).isEqualTo(0.6);
    }

    @Test
    void archiveParsesMicrosecondTimestamps() throws Exception {
        Series s = new Series("BTCUSDT", 1_759_276_800_000L, 10);
        String csv = "1759276800000000,114048.94,114308.00,114048.93,114156.18,117.9,1759277699999999,13467553.5,26048,75,8572232,0\n"
                + "1759277700000,114156.17,114177.93,113966.67,114021.86,120.2,1759278599999,13710429.8,30447,47,5464744,0\n";
        assertThat(Archive.parse(new java.io.BufferedReader(new java.io.StringReader(csv)), s)).isTrue();
        assertThat(s.close[0]).isEqualTo(114156.18f);
        assertThat(s.close[1]).isEqualTo(114021.86f);
        assertThat(s.has(2)).isFalse();
    }

    @Test
    void hourlyBarsUseOnlyClosedHours() {
        Series s = new Series("X", 0, 16);
        for (int i = 0; i < 16; i++) s.set(i, i + 1, i + 2, i, i + 1.5, 10);
        // 第 9 根（第 3 个小时的第 2 根）收盘时，只有前 2 个小时完整
        var h = s.hours(9, 2);
        assertThat(h).hasSize(2);
        assertThat(h.get(1).openTime()).isEqualTo(Series.H);
        assertThat(h.get(1).close()).isEqualTo(8.5);
    }
}
