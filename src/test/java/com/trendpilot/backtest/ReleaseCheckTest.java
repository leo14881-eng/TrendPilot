package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.variant.VariantOverrides;

/** 发布前检查的判定，以及回测里的参数改动和线上同一份代码。 */
class ReleaseCheckTest {

    static ReleaseCheck.Period p(double eq, double dd, int n, double mean, double var) {
        return new ReleaseCheck.Period(eq, dd, n, mean, var);
    }

    static final double[] REG = {0, 1000, 1000, 1000, 1000, 1000, 1000};

    @Test
    void passesOnlyWhenBetterInBothPeriodsSignificantAndNoWorseRisk() {
        var base = p(10_000, 5_000, 5_000, 2, 100);
        var baseV = p(1_000, 3_000, 1_500, 0.7, 100);
        var rnd = p(-10_000, 20_000, 5_000, -2, 100);
        var good = ReleaseCheck.evaluate(p(15_000, 5_200, 5_000, 3, 100), p(2_000, 3_100, 1_500, 1.3, 100), base, baseV, REG, REG, rnd);
        assertThat(good.verdict()).isEqualTo(ReleaseCheck.Verdict.PASS);

        var deeper = ReleaseCheck.evaluate(p(15_000, 6_000, 5_000, 3, 100), p(2_000, 3_100, 1_500, 1.3, 100), base, baseV, REG, REG, rnd);
        assertThat(deeper.verdict()).isEqualTo(ReleaseCheck.Verdict.PROMISING);
        assertThat(deeper.checks().get("最大回撤不超过基准 ×1.1")).isFalse();

        double[] badRegime = REG.clone();
        badRegime[2] = -1_000;
        var regime = ReleaseCheck.evaluate(p(15_000, 5_000, 5_000, 3, 100), p(2_000, 3_000, 1_500, 1.3, 100), base, baseV, badRegime, REG, rnd);
        assertThat(regime.verdict()).isEqualTo(ReleaseCheck.Verdict.PROMISING);
        assertThat(regime.notes()).anyMatch(n -> n.contains("山寨强于BTC"));

        var worse = ReleaseCheck.evaluate(p(8_000, 6_000, 5_000, 1.6, 100), p(500, 3_500, 1_500, 0.3, 100), base, baseV, REG, REG, rnd);
        assertThat(worse.verdict()).isEqualTo(ReleaseCheck.Verdict.WORSE);
        // 更好但差值不显著：不能算"证明更好"
        var noise = ReleaseCheck.evaluate(p(10_100, 5_000, 5_000, 2.02, 100), p(1_010, 3_000, 1_500, 0.71, 100), base, baseV, REG, REG, rnd);
        assertThat(noise.verdict()).isEqualTo(ReleaseCheck.Verdict.PROMISING);
    }

    @Test
    void emptyOverridesReproduceTheCurrentRulesExactly() throws Exception {
        StrategyParams params = BacktestMain.loadParams();
        MarketData data = BacktestSmokeTest.synthetic(30, 40, 11);
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        int fromQ = 10 * 96;
        B0Sim a = EntryStudy.live("现行", data, regimes, tape, params, fromQ, data.size);
        B0Sim b = EntryStudy.live("空改动", data, regimes, tape, params, fromQ, data.size).overrides(VariantOverrides.NONE);
        B0Sim c = EntryStudy.live("H2", data, regimes, tape, params, fromQ, data.size)
                .overrides(VariantOverrides.parseInline("early-exit-hours=4,early-exit-max-mfe-pct=1,early-exit-loss-pct=0.5"));
        B0Sim r = EntryStudy.live("让赢家跑", data, regimes, tape, params, fromQ, data.size)
                .overrides(VariantOverrides.parseInline("runner-arm-pct=2.5,runner-giveback-pct=3"));
        B0Sim x = EntryStudy.live("S1", data, regimes, tape, params, fromQ, data.size)
                .overrides(VariantOverrides.parseInline("exit-rule=S1_L8"));
        B0Sim f = EntryStudy.live("15分钟", data, regimes, tape, params, fromQ, data.size).discoverEvery(1);
        for (B0Sim s : List.of(a, b, c, r, x, f)) s.run();
        assertThat(a.trades()).isNotEmpty();
        assertThat(b.trades()).isEqualTo(a.trades());
        assertThat(c.trades()).anyMatch(t -> t.reason().equals(VariantOverrides.EARLY_EXIT));
        assertThat(r.trades()).isNotEqualTo(a.trades());
        assertThat(f.trades()).isNotEmpty();
        for (B0Sim s : List.of(r, x, f)) {
            assertThat(s.equity()[data.size / 4]).isCloseTo(s.trades().stream().mapToDouble(Trade::pnl).sum(),
                    org.assertj.core.data.Offset.offset(1e-6));
        }
        B0Sim m = CandidateStudy.parse("M", "momentum", "sim.momentum-top=5,sim.momentum-hours=24,sim.stop-fixed=5,sim.trail-atr=2")
                .apply(EntryStudy.live("动量", data, regimes, tape, params, fromQ, data.size));
        m.run();
        assertThat(m.trades()).isNotEmpty().anyMatch(t -> t.reason().equals("TRAIL_ATR") || t.reason().startsWith("L1_"));
        CandidateStudy.Candidate all = CandidateStudy.relax().get(4);
        StrategyParams relaxed = BacktestMain.loadParams(all.config());
        assertThat(relaxed.capital().deployPct("CONSTRUCTIVE")).isEqualTo(0.95);
        assertThat(relaxed.review().minScore()).isEqualTo(7);
        assertThat(relaxed.risk().lossExitsFreeze()).isEqualTo(999);
        assertThat(all.overrides().minDepthUsdt()).isEqualTo(15000.0);
        assertThat(CandidateStudy.parse("F", "g", "cfg.risk.loss-exits-freeze=999,sim.discover-every=1,max-volume-accel=4"))
                .satisfies(k -> {
                    assertThat(k.config()).containsEntry("trendpilot.strategy.risk.loss-exits-freeze", "999");
                    assertThat(k.discoverEvery()).isEqualTo(1);
                    assertThat(k.overrides().maxVolumeAccel()).isEqualTo(4.0);
                    assertThat(k.liveCapable()).isFalse();
                });
    }

    /** 每笔均值 mean、95% 半宽 half、n 笔的时段（方差按半宽反推）。 */
    static ReleaseCheck.Period ci(double eq, double dd, int n, double mean, double half) {
        double var = Math.pow(half / 1.96, 2) * n;
        return new ReleaseCheck.Period(eq, dd, n, mean, var);
    }

    @Test
    void executionSensitivityCapsTheVerdictAtPromising() {
        var base = p(10_000, 5_000, 5_000, 2, 100);
        var baseV = p(1_000, 3_000, 1_500, 0.7, 100);
        var rnd = p(-10_000, 20_000, 5_000, -2, 100);
        var cand = p(15_000, 5_200, 5_000, 3, 100);
        var candV = p(2_000, 3_100, 1_500, 1.3, 100);
        var base75 = ci(-15_000, 16_000, 5_000, -3, 0.3);

        // 75% 下仍然为正且显著：PASS，两组数字都带出来
        var strong = ReleaseCheck.evaluate(cand, candV, base, baseV, REG, REG, rnd, new ReleaseCheck.Sensitivity(ci(9_000, 5_000, 5_000, 1.8, 0.3), base75));
        assertThat(strong.verdict()).isEqualTo(ReleaseCheck.Verdict.PASS);
        assertThat(strong.checks()).containsEntry(ReleaseCheck.WICK_CHECK, true);
        assertThat(strong.wickOwn()[0]).isEqualTo(1.8);
        assertThat(strong.wickDiff()[0]).isCloseTo(4.8, org.assertj.core.data.Offset.offset(1e-9));

        // 全期合计 ≤ 0
        var flat = ReleaseCheck.evaluate(cand, candV, base, baseV, REG, REG, rnd, new ReleaseCheck.Sensitivity(ci(0, 9_000, 5_000, 0, 0.3), base75));
        assertThat(flat.verdict()).isEqualTo(ReleaseCheck.Verdict.PROMISING);
        assertThat(flat.checks()).containsEntry(ReleaseCheck.WICK_CHECK, false);
        assertThat(flat.notes()).anyMatch(n -> n.startsWith("上影线 75%"));
        // 为正但每笔 95% 区间含 0
        var thin = ReleaseCheck.evaluate(cand, candV, base, baseV, REG, REG, rnd, new ReleaseCheck.Sensitivity(ci(1_000, 9_000, 5_000, 0.2, 0.5), base75));
        assertThat(thin.verdict()).isEqualTo(ReleaseCheck.Verdict.PROMISING);
        // 自己显著为正，但对基准的差值区间含 0
        var noEdge = ReleaseCheck.evaluate(cand, candV, base, baseV, REG, REG, rnd,
                new ReleaseCheck.Sensitivity(ci(9_000, 5_000, 5_000, 1.8, 0.3), ci(9_000, 5_000, 5_000, 1.8, 0.3)));
        assertThat(noEdge.verdict()).isEqualTo(ReleaseCheck.Verdict.PROMISING);
        // 没跑敏感度：不能 PASS
        var missing = ReleaseCheck.evaluate(cand, candV, base, baseV, REG, REG, rnd, null);
        assertThat(missing.verdict()).isEqualTo(ReleaseCheck.Verdict.PROMISING);
        assertThat(missing.notes()).contains("没有跑上影线 75% 敏感度");
        // 原来就更差的仍是 WORSE（敏感度只会往下压，不会往上抬）
        var worse = ReleaseCheck.evaluate(p(8_000, 6_000, 5_000, 1.6, 100), p(500, 3_500, 1_500, 0.3, 100), base, baseV, REG, REG, rnd,
                new ReleaseCheck.Sensitivity(ci(9_000, 5_000, 5_000, 1.8, 0.3), base75));
        assertThat(worse.verdict()).isEqualTo(ReleaseCheck.Verdict.WORSE);
    }

    @Test
    void currentCandidatesFromTheReconciliationFailTheSensitivity() {
        // docs/策略对比记录/2026-11-08-回测与实盘对账.md 第 4 节：上影线 75% 的 4 年数字
        var base75 = ci(-17_000, 18_000, 4_000, -4.6, 2.13);
        var v5 = new ReleaseCheck.Sensitivity(ci(1_738, 21_964, 7_900, 0.22, 3.25), base75);
        var v6 = new ReleaseCheck.Sensitivity(ci(11_076, 7_217, 13_674, 0.81, 1.17), base75);
        assertThat(ReleaseCheck.sensitivityHolds(v5)).isFalse();
        assertThat(ReleaseCheck.sensitivityHolds(v6)).isFalse();
        // 原口径（100%）：V6 +4.38 ±1.05 本身是显著的
        assertThat(ReleaseCheck.sensitivityHolds(new ReleaseCheck.Sensitivity(ci(67_083, 4_101, 15_300, 4.38, 1.05), base75))).isTrue();
    }

    @Test
    void weeklyRunAddsAQuarterWickReplayForEveryCandidate() throws Exception {
        StrategyParams params = BacktestMain.loadParams();
        MarketData data = BacktestSmokeTest.synthetic(30, 30, 17);
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        CandidateStudy.Candidate c = CandidateStudy.parse("M", "momentum", "momentum-top=3,momentum-hours=24,max-open=17");
        B0Sim full = c.apply(EntryStudy.live("M", data, regimes, tape, params, 10 * 96, data.size));
        B0Sim w = CandidateStudy.sensitivity(c, EntryStudy.live(CandidateStudy.wickName("M"), data, regimes, tape, params, 10 * 96, data.size));
        B0Sim explicit = c.apply(EntryStudy.live("M2", data, regimes, tape, params, 10 * 96, data.size)).wick(0.75);
        full.run();
        w.run();
        explicit.run();
        assertThat(w.trades()).isEqualTo(explicit.trades()).isNotEqualTo(full.trades());
        assertThat(CandidateStudy.isWick(w.name())).isTrue();
        assertThat(CandidateStudy.isWick(full.name())).isFalse();
        // 候选自己已经更低（研究用）时保持更低
        CandidateStudy.Candidate low = CandidateStudy.parse("L", "recon", "momentum-top=3,momentum-hours=24,max-open=17,sim.wick=0.5");
        B0Sim lw = CandidateStudy.sensitivity(low, EntryStudy.live("L 上影75", data, regimes, tape, params, 10 * 96, data.size));
        B0Sim half = c.apply(EntryStudy.live("H", data, regimes, tape, params, 10 * 96, data.size)).wick(0.5);
        lw.run();
        half.run();
        assertThat(lw.trades()).isEqualTo(half.trades());

        // candidates.json：100% 和 75% 两组数字
        ReleaseCheck.Period f = ReleaseCheck.Period.of(full, regimes, 10 * 96, data.size, 20_000);
        ReleaseCheck.Period fw = ReleaseCheck.Period.of(w, regimes, 10 * 96, data.size, 20_000);
        ReleaseCheck.Sensitivity sens = new ReleaseCheck.Sensitivity(fw, fw);
        var r = ReleaseCheck.evaluate(f, f, f, f, REG, REG, f, sens);
        var j = CandidateStudy.sensitivityJson(f, sens, r);
        assertThat(j).containsKeys("wickShare", "pass", "wick100", "wick75").containsEntry("wickShare", 0.75).containsEntry("pass", false);
        assertThat(r.verdict()).isNotEqualTo(ReleaseCheck.Verdict.PASS);
    }
}
