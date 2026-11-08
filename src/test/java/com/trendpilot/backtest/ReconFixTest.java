package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.signal.EarlySignalCalculator;

/** Backtest vs live reconciliation (2026-11-08): partial upper wick for profit protection, max-open for momentum buys, run parsing. */
class ReconFixTest {

    @Test
    void reachableHighKeepsTheBodyAndCountsOnlyPartOfTheUpperWick() {
        assertThat(B0Sim.reachableHigh(1.0, 2.0, 1.2, 1.0)).isEqualTo(2.0);
        assertThat(B0Sim.reachableHigh(1.0, 2.0, 1.2, 0.5)).isCloseTo(1.6, Offset.offset(1e-12));
        assertThat(B0Sim.reachableHigh(1.3, 2.0, 1.2, 0.0)).isEqualTo(1.3);
        assertThat(B0Sim.reachableHigh(1.0, 1.2, 1.2, 0.5)).isEqualTo(1.2); // no upper wick
        StrategyParams params = paramsOrNull();
        assertThatThrownBy(() -> EntryStudy.live("x", BacktestSmokeTest.synthetic(10, 10, 1), null, null, params, 0, 1).wick(1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wickSwitchChangesProfitProtectionButKeepsTrading() throws Exception {
        StrategyParams params = BacktestMain.loadParams();
        MarketData data = BacktestSmokeTest.synthetic(30, 30, 17);
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        List<List<Trade>> runs = new java.util.ArrayList<>();
        for (String w : new String[]{"", ",sim.wick=1", ",sim.wick=0.5"}) {
            B0Sim sim = CandidateStudy.parse("w", "recon", "momentum-top=3,momentum-hours=24,max-open=17" + w)
                    .apply(EntryStudy.live("w", data, regimes, tape, params, 10 * 96, data.size));
            sim.run();
            assertThat(sim.trades()).isNotEmpty();
            runs.add(sim.trades());
        }
        assertThat(runs.get(1)).as("wick=1 is the old behaviour").isEqualTo(runs.get(0));
        assertThat(runs.get(2)).as("half wick changes the result").isNotEqualTo(runs.get(0));
    }

    @Test
    void momentumBuysRespectMaxOpenOnlyWhenEnforced() throws Exception {
        StrategyParams params = BacktestMain.loadParams();
        MarketData data = BacktestSmokeTest.synthetic(40, 45, 7);
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        B0Sim free = CandidateStudy.parse("m", "recon", "momentum-top=5,momentum-hours=24,max-open=2")
                .apply(EntryStudy.live("m", data, regimes, tape, params, 10 * 96, data.size));
        B0Sim capped = CandidateStudy.parse("m", "recon", "momentum-top=5,momentum-hours=24,max-open=2,sim.enforce-max-open=1")
                .apply(EntryStudy.live("m", data, regimes, tape, params, 10 * 96, data.size));
        free.run();
        capped.run();
        assertThat(maxConcurrent(free.trades())).isGreaterThan(2);
        assertThat(maxConcurrent(capped.trades())).isLessThanOrEqualTo(2);
    }

    @Test
    void parsesRunsWithExactStartTimes() {
        ReconStudy.Run r = ReconStudy.parseRun("V5@2026-10-23T09:00:00Z#min-buy-rel1h=5.855,trade-usdt=2000,sim.wick=0.5");
        assertThat(r.name()).isEqualTo("V5");
        assertThat(r.startMs()).isEqualTo(java.time.Instant.parse("2026-10-23T09:00:00Z").toEpochMilli());
        assertThat(r.endMs()).isEqualTo(Long.MAX_VALUE);
        assertThat(r.cand().overrides().minBuyRel1hOr(0)).isEqualTo(5.855);
        assertThat(r.cand().sim()).containsEntry("wick", "0.5");
        ReconStudy.Run d = ReconStudy.parseRun("V6@2022-10-01@2026-11-01");
        assertThat(d.endMs()).isEqualTo(java.time.Instant.parse("2026-11-01T00:00:00Z").toEpochMilli());
        assertThat(d.cand().overrides().isEmpty()).isTrue();
    }

    /** Most positions open at the same time (a position occupies [entryQ, exitQ)). */
    static int maxConcurrent(List<Trade> trades) {
        int best = 0;
        for (Trade t : trades) {
            int at = t.entryQ();
            int n = (int) trades.stream().filter(x -> x.entryQ() <= at && x.exitQ() > at).count();
            best = Math.max(best, n);
        }
        return best;
    }

    private static StrategyParams paramsOrNull() {
        try {
            return BacktestMain.loadParams();
        } catch (Exception e) {
            return null;
        }
    }
}
