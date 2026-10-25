package com.trendpilot.variant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 变体参数改动：读写、开仓过滤（H1/H4）、提前离场（H2），以及数据缺失时不动作。 */
class VariantOverridesTest {

    @Test
    void parsesRoundTripsAndRejectsTypos() {
        VariantOverrides o = VariantOverrides.parse(Map.of("coin24h-band-pct", "5-10", "hot-entry", "half", "trade-usdt", 1500));
        assertThat(o.coin24hMinPct()).isEqualTo(5.0);
        assertThat(o.coin24hMaxPct()).isEqualTo(10.0);
        assertThat(o.hotEntry()).isEqualTo(VariantOverrides.HotEntry.HALF);
        assertThat(o.inline()).isEqualTo("coin24h-band-pct=5-10,hot-entry=HALF,trade-usdt=1500");
        assertThat(VariantOverrides.parseInline(o.inline())).isEqualTo(o);
        assertThat(VariantOverrides.parse(Map.of()).isEmpty()).isTrue();
        assertThatThrownBy(() -> VariantOverrides.parse(Map.of("min-buy-rel", "5"))).hasMessageContaining("不认识");
        assertThatThrownBy(() -> VariantOverrides.parse(Map.of("early-exit-hours", "12"))).hasMessageContaining("一起设置");
        assertThatThrownBy(() -> VariantOverrides.parse(Map.of("coin24h-band-pct", "10-5"))).hasMessageContaining("低-高");
        assertThatThrownBy(() -> VariantOverrides.parse(Map.of("trade-usdt", "9000"))).hasMessageContaining("5000");
        assertThat(VariantOverrides.merge(VariantOverrides.parseInline("min-buy-rel1h=5.855,trade-usdt=2000"),
                VariantOverrides.parseInline("trade-usdt=1500")).inline()).isEqualTo("min-buy-rel1h=5.855,trade-usdt=1500");
    }

    @Test
    void entryFilterSkipsOrHalvesHotEntriesAndTreatsMissingDataAsNoBuy() {
        VariantOverrides skip = VariantOverrides.parseInline("btc24h-above-pct=1.5,coin24h-band-pct=5-10");
        assertThat(skip.entryMultiplier(1.0, 3, 2)).isEqualTo(1);
        assertThat(skip.entryMultiplier(2.0, 3, 2)).isZero();
        assertThat(skip.entryMultiplier(1.0, 7, 2)).isZero();
        assertThat(skip.entryMultiplier(1.0, 12, 2)).isEqualTo(1); // 涨超 10% 不在区间里
        assertThat(skip.entryMultiplier(Double.NaN, 3, 2)).isZero();
        VariantOverrides half = VariantOverrides.parseInline("btc24h-above-pct=1.5,hot-entry=HALF");
        assertThat(half.entryMultiplier(2.0, 3, 2)).isEqualTo(0.5);
        VariantOverrides cap = VariantOverrides.parseInline("max-volume-accel=4");
        assertThat(cap.entryMultiplier(Double.NaN, Double.NaN, 3.9)).isEqualTo(1);
        assertThat(cap.entryMultiplier(0, 0, 4.5)).isZero();
        assertThat(cap.entryMultiplier(0, 0, Double.NaN)).isZero();
        assertThat(VariantOverrides.NONE.entryMultiplier(Double.NaN, Double.NaN, Double.NaN)).isEqualTo(1);
    }

    @Test
    void earlyExitNeedsHoldTimeNoProfitAndLossBeyondLine() {
        VariantOverrides o = VariantOverrides.parseInline("early-exit-hours=24,early-exit-max-mfe-pct=1,early-exit-loss-pct=4");
        Duration day = Duration.ofHours(24);
        assertThat(o.earlyExit(day, false, 5, 1000, -5)).isTrue();
        assertThat(o.earlyExit(Duration.ofHours(23), false, 5, 1000, -5)).isFalse(); // 没满 24 小时
        assertThat(o.earlyExit(day, false, 10, 1000, -5)).isFalse();  // 曾经浮盈达到 1%
        assertThat(o.earlyExit(day, false, 5, 1000, -3)).isFalse();   // 亏损没超过 4%
        assertThat(o.earlyExit(day, true, 5, 1000, -5)).isFalse();    // 利润保护已启动
        assertThat(o.earlyExit(day, false, Double.NaN, 1000, -5)).isFalse();
        assertThat(o.earlyExit(day, false, 5, 1000, Double.NaN)).isFalse();
        assertThat(VariantOverrides.NONE.earlyExit(day, false, -50, 1000, -50)).isFalse();
    }

    @Test
    void exitRuleReusesTrackingRuleDefinitions() {
        VariantOverrides c1 = VariantOverrides.parseInline("exit-rule=C1_L15_T24");
        assertThat(c1.hasEarlyExit()).isTrue();
        assertThat(c1.extraExit(Duration.ofHours(2), false, 0, 1000, -16, -15.8)).isEqualTo("RULE_C1_L15_T24");
        assertThat(c1.extraExit(Duration.ofHours(2), false, 0, 1000, -5, -4.8)).isNull();
        assertThat(c1.extraExit(Duration.ofHours(24), false, 0, 1000, -0.5, -0.3)).isEqualTo("RULE_C1_L15_T24");
        assertThat(c1.extraExit(Duration.ofHours(24), false, 0, 1000, Double.NaN, -0.3)).isNull();
        VariantOverrides s1 = VariantOverrides.parseInline("exit-rule=S1_L12");
        assertThat(s1.extraExit(Duration.ofHours(1), false, 0, 1000, -12.5, -12)).isEqualTo("RULE_S1_L12");
        assertThatThrownBy(() -> VariantOverrides.parseInline("exit-rule=P1_a5_D10")).hasMessageContaining("S1/C1/T1/T2");
        assertThatThrownBy(() -> VariantOverrides.parseInline("exit-rule=C9")).hasMessageContaining("未知");
    }
}
