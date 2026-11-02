package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.signal.EarlySignalCalculator;

/** Optimisation O1 (backtest only): no new position when the coin already rose this much in the 4 hours before the buy. */
class ChaseCapTest {

    @Test
    void ret4hUsesOnlyClosedBarsAndMissingDataMeansNoValue() {
        Series s = new Series("X1USDT", 0, 40);
        for (int i = 0; i < 40; i++) s.set(i, 1, 1, 1, i < 20 ? 1.0 : 1.2, 1000);
        assertThat(B0Sim.ret4hPct(s, 36)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(B0Sim.ret4hPct(s, 30)).isCloseTo(20.0, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(B0Sim.ret4hPct(s, 10)).isNaN();
        assertThat(B0Sim.ret4hPct(null, 30)).isNaN();
    }

    @Test
    void capFiltersHotEntriesForRuleAndRandomControl() throws Exception {
        StrategyParams params = BacktestMain.loadParams();
        MarketData data = BacktestSmokeTest.synthetic(40, 45, 7);
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        int fromQ = 10 * 96;
        for (String rule : List.of("min-buy-rel1h=0", "momentum-top=3,momentum-hours=24,max-open=17")) {
            for (boolean random : new boolean[]{false, true}) {
                CandidateStudy.Candidate c = CandidateStudy.parse("cap", "O1", rule + ",sim.max-ret4h-pct=3,sim.base=x");
                B0Sim sim = c.apply(EntryStudy.live("cap", data, regimes, tape, params, fromQ, data.size));
                if (random) sim.randomEntry(42);
                sim.run();
                for (Trade t : sim.trades()) {
                    if (t.tranches() > 1) continue;
                    assertThat(B0Sim.ret4hPct(data.get(t.symbol()), t.entryQ())).as(rule + " " + t.symbol()).isLessThan(3.0);
                }
                assertThat(sim.trades()).as(rule + " still trades").isNotEmpty();
                assertThat(c.baseId()).isEqualTo("x");
                assertThat(c.liveCapable()).isFalse();
            }
        }
        // without the cap the same rules do buy coins that rose >= 3% in 4h, so the cap is what keeps them out
        for (String rule : List.of("min-buy-rel1h=0", "momentum-top=3,momentum-hours=24,max-open=17")) {
            B0Sim open = CandidateStudy.parse("open", "O1", rule).apply(EntryStudy.live("open", data, regimes, tape, params, fromQ, data.size));
            open.run();
            assertThat(open.trades()).as(rule).anyMatch(t -> B0Sim.ret4hPct(data.get(t.symbol()), t.entryQ()) >= 3.0);
        }
        assertThat(CandidateStudy.chase()).extracting(CandidateStudy.Candidate::id).containsExactly(
                "O1_V5基准", "O1_V6基准", "O1_V5_4h≥15%不开", "O1_V6_4h≥15%不开", "O1_V5_4h≥20%不开", "O1_V6_4h≥20%不开");
    }
}
