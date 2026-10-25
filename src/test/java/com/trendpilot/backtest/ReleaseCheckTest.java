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
        for (B0Sim s : List.of(a, b, c)) s.run();
        assertThat(a.trades()).isNotEmpty();
        assertThat(b.trades()).isEqualTo(a.trades());
        assertThat(c.trades()).anyMatch(t -> t.reason().equals(VariantOverrides.EARLY_EXIT));
    }
}
