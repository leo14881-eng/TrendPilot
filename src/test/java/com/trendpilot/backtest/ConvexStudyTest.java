package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.trendpilot.config.StrategyParams;

/** "Big wins, small losses" research rules (backtest only, never traded live). */
class ConvexStudyTest {

    static final long START = 1_756_684_800_000L; // 2025-09-01 00:00 UTC

    @Test
    void indicatorsUseOnlyCompleteClosedDays() {
        double[] c = {1, 2, 3, 4, Double.NaN, 6};
        assertThat(ConvexSim.sma(c, 3, 4)).isEqualTo(2.5);
        assertThat(ConvexSim.sma(c, 4, 2)).isNaN();
        assertThat(ConvexSim.sma(c, 1, 3)).isNaN();
        assertThat(ConvexSim.ret(c, 3, 2)).isCloseTo(1.0, offset(1e-12));
        assertThat(ConvexSim.stopDistance(1, 2.5)).isEqualTo(0.04);
        assertThat(ConvexSim.stopDistance(30, 2.5)).isEqualTo(0.35);
        assertThat(ConvexSim.stopDistance(6, 2.5)).isCloseTo(0.15, offset(1e-12));
        assertThat(ConvexSim.unitNotional(5)).isCloseTo(1000, offset(1e-9)); // 2 x 5% = 10% stop, 100 U risk
        assertThat(ConvexSim.unitNotional(1)).isEqualTo(2500);
        assertThat(ConvexSim.maShare(20_000, 5, false)).isEqualTo(800);
        assertThat(ConvexSim.maShare(20_000, 10, true)).isEqualTo(400);
        assertThat(ConvexSim.maShare(20_000, 1, true)).isEqualTo(1600);
    }

    @Test
    void dailyBarsAggregateFifteenMinuteBarsAndSkipEmptyDays() {
        Series s = new Series("X", START, 3 * 96);
        for (int i = 0; i < 96; i++) s.set(i, 1 + i * 0.01, 2 + i * 0.01, 0.5, 1.5 + i * 0.01, 100);
        for (int i = 192; i < 200; i++) s.set(i, 3, 3.5, 2.5, 3.2, 10);
        ConvexSim.Daily d = new ConvexSim.Daily(s, 3);
        assertThat(d.open[0]).isEqualTo(1.0);
        assertThat(d.high[0]).isCloseTo(2.95, offset(1e-5));
        assertThat(d.close[0]).isCloseTo(2.45, offset(1e-5));
        assertThat(d.quote[0]).isEqualTo(9600);
        assertThat(d.close[1]).isNaN();
        assertThat(d.close[2]).isCloseTo(3.2, offset(1e-6));
        assertThat(ConvexSim.priorHigh(d, 2, 2)).isNaN(); // a missing day means no value, so no action
        assertThat(ConvexSim.atrPct(d, 2)).isNaN();
    }

    @Test
    void gridIsTwelvePreRegisteredSets() {
        List<ConvexSim.Rule> g = ConvexStudy.grid();
        assertThat(g).hasSize(12);
        assertThat(g.stream().map(ConvexSim.Rule::id).distinct()).hasSize(12);
        assertThat(g.stream().filter(r -> r.family().equals("A"))).hasSize(4);
        assertThat(g.stream().filter(r -> r.family().equals("B"))).hasSize(4);
        assertThat(g.stream().filter(r -> r.family().equals("C"))).hasSize(4);
    }

    /** BTC rising, 24 flat coins, X breaks its 20-day high on day 110 and is knocked to the stop on day 112. */
    static MarketData breakoutMarket(boolean dropEntryBar) {
        int days = 130, size = days * 96;
        Map<String, Series> m = new HashMap<>();
        java.util.Random rnd = new java.util.Random(3);
        for (int k = 0; k <= 25; k++) {
            String sym = k == 0 ? MarketData.BTC : k == 1 ? "XUSDT" : "C" + k + "USDT";
            Series s = new Series(sym, START, size);
            for (int i = 0; i < size; i++) {
                int day = i / 96;
                double p = k == 0 ? 50_000 * (1 + 0.003 * day) : 1 + 0.002 * Math.sin(i * 0.37 + k) + 0.001 * rnd.nextGaussian();
                if (k == 1 && day >= 110) p = 1.2;
                double lo = p * 0.998, hi = p * 1.002, o = p;
                if (k == 1 && day == 112 && i % 96 == 40) lo = 1.0; // touches the stop inside the bar
                if (k == 1 && day > 112) p = lo = hi = o = 1.0;
                if (k == 1 && dropEntryBar && i == 111 * 96) continue;
                s.set(i, o, hi, lo, p, 10_000_000.0 / 96);
            }
            m.put(sym, s);
        }
        return new MarketData(START, size, m, 150, Set.of());
    }

    @Test
    void breakoutBuysAtNextDayOpenAndTheStopCapsTheLoss() throws Exception {
        StrategyParams params = BacktestMain.loadParams();
        MarketData data = breakoutMarket(false);
        Regimes regimes = new Regimes(data);
        ConvexSim sim = new ConvexSim(ConvexSim.Rule.breakout(20, 10), data, regimes, params, ConvexSim.Daily.of(data), 30 * 96, data.size, 1);
        sim.run();
        List<Trade> x = sim.trades().stream().filter(t -> t.symbol().equals("XUSDT")).toList();
        assertThat(x).isNotEmpty();
        Trade t = x.get(0);
        assertThat(t.entryQ()).isEqualTo(111 * 96); // signal from day 110's close, filled at day 111's first bar
        assertThat(t.reason()).isEqualTo("STOP_LOSS");
        assertThat(t.exitQ()).isEqualTo(112 * 96 + 40);
        assertThat(t.pct()).isBetween(-5.0, -3.9); // flat coin: ATR tiny, stop clamped to 4%
        // the random twin bought the same day with the same amount and is out no later than the real trade
        List<Trade> tw = sim.twin().trades().stream().filter(r -> r.entryQ() == t.entryQ()).toList();
        assertThat(tw).hasSize(1);
        assertThat(tw.get(0).symbol()).isNotEqualTo("XUSDT");
        assertThat(tw.get(0).cost()).isCloseTo(t.cost(), offset(1e-6));
        assertThat(tw.get(0).exitQ()).isLessThanOrEqualTo(t.exitQ() + 1);
        assertThat(sim.equity()[data.size / 4]).isCloseTo(sim.trades().stream().mapToDouble(Trade::pnl).sum(), offset(1e-6));
    }

    @Test
    void missingEntryBarMeansNoBuyThatDay() throws Exception {
        StrategyParams params = BacktestMain.loadParams();
        MarketData data = breakoutMarket(true);
        ConvexSim sim = new ConvexSim(ConvexSim.Rule.breakout(20, 10), data, new Regimes(data), params, ConvexSim.Daily.of(data), 30 * 96, data.size, 1);
        sim.run();
        assertThat(sim.trades()).noneMatch(t -> t.symbol().equals("XUSDT") && t.entryQ() == 111 * 96);
    }

    @Test
    void allRulesRunWithBoundedLossesAndAReport() throws Exception {
        StrategyParams params = BacktestMain.loadParams();
        MarketData data = BacktestSmokeTest.synthetic(30, 140, 5);
        String md = ConvexStudy.study(data, params, java.time.LocalDate.of(2025, 10, 1), java.time.LocalDate.of(2025, 12, 1),
                java.time.LocalDate.of(2026, 1, 19), 2, "synthetic", null);
        assertThat(md).contains("盈亏结构", "发布前检查", "六种行情", "三段山寨行情", "滚动前推", "保本胜率", "377");
        Map<String, ConvexSim.Daily> daily = ConvexSim.Daily.of(data);
        Regimes regimes = new Regimes(data);
        int traded = 0;
        for (ConvexSim.Rule r : ConvexStudy.grid()) {
            ConvexSim sim = new ConvexSim(r, data, regimes, params, daily, 10 * 96, data.size, 7);
            sim.run();
            if (!sim.trades().isEmpty()) traded++;
            for (Trade t : sim.trades()) {
                // a hard stop caps every exit: at most the 35% stop plus costs and a gap on smooth synthetic data
                assertThat(t.pct()).as(r.id() + " " + t.reason()).isGreaterThan(-40);
                assertThat(t.exitQ()).isGreaterThanOrEqualTo(t.entryQ());
                assertThat(t.entryQ() % 96).as("entries only at the daily open").isZero();
            }
            assertThat(java.util.Arrays.stream(sim.deployed()).max().orElse(0)).isLessThanOrEqualTo(params.capital().hardCap() * 1.001);
        }
        assertThat(traded).isGreaterThan(0);
    }

    @Test
    void significanceHelpers() {
        assertThat(ConvexStudy.pValue(1.96)).isCloseTo(0.05, offset(1e-3));
        assertThat(ConvexStudy.pValue(0)).isCloseTo(1.0, offset(1e-6));
    }
}
