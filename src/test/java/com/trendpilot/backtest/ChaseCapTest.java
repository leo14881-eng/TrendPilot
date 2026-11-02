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

    @Test
    void v6ExitGridKeepsTheV6BuyRuleAndComparesAgainstIt() throws Exception {
        List<CandidateStudy.Candidate> g = CandidateStudy.v6Exit();
        assertThat(g).hasSize(7);
        assertThat(g).allMatch(c -> c.overrides().isMomentum() && c.overrides().momentumTop() == 3 && c.overrides().maxOpen() == 17);
        assertThat(g.subList(1, 7)).allMatch(c -> c.baseId().equals("O3_V6现行卖法") && !c.liveCapable());
        StrategyParams params = BacktestMain.loadParams();
        MarketData data = BacktestSmokeTest.synthetic(30, 30, 11);
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        for (CandidateStudy.Candidate c : g) {
            B0Sim sim = c.apply(EntryStudy.live(c.id(), data, regimes, tape, params, 10 * 96, data.size));
            sim.run();
            assertThat(sim.trades()).as(c.id()).isNotEmpty();
            if (c.id().contains("12h")) {
                assertThat(sim.trades()).anyMatch(t -> t.reason().startsWith("L1_STALE"));
            }
        }
        assertThat(CandidateStudy.payoffTable(List.of(), 0, 0)).contains("盈亏比");
    }

    @Test
    void strongRegimeExitGridRunsAndReportsBigWinsWindowsAndAltMarket() throws Exception {
        List<CandidateStudy.Candidate> g = CandidateStudy.strongExit();
        assertThat(g).hasSize(6);
        assertThat(g.subList(1, 6)).allMatch(c -> c.sim().get("runner-regimes").equals("1+2") && c.overrides().hasRunner());
        StrategyParams params = BacktestMain.loadParams();
        MarketData data = BacktestSmokeTest.synthetic(30, 30, 13);
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        List<Sim> sims = new java.util.ArrayList<>();
        for (CandidateStudy.Candidate c : g) {
            B0Sim sim = c.apply(EntryStudy.live(c.id(), data, regimes, tape, params, 10 * 96, data.size));
            sim.run();
            assertThat(sim.trades()).as(c.id()).isNotEmpty();
            sims.add(sim);
        }
        assertThat(CandidateStudy.bigWins(sims, regimes, 10 * 96)).contains("≥ 100%");
        assertThat(CandidateStudy.windows(sims, data)).contains("2024-11-01");
        assertThat(CandidateStudy.altMarket(data, 10 * 96)).contains("50 日线上方");
    }
}
