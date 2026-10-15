package com.trendpilot.v2n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import org.junit.jupiter.api.Test;

class V2nRulesTest {

    private final V2nParams p = V2nParams.base();

    @Test
    void stopDistanceIsClampedAndTooVolatileIsRejected() {
        assertThat(V2nRules.stopPct(p, 1.0)).isEqualTo(4.0);   // 3% → 下限 4%
        assertThat(V2nRules.stopPct(p, 2.0)).isEqualTo(6.0);
        assertThat(V2nRules.stopPct(p, 5.0)).isNaN();          // 15% > 12%：太疯不买
    }

    @Test
    void sizeKeepsLossAtOneR() {
        assertThat(V2nRules.size(p, 4, 1)).isEqualTo(2000);
        assertThat(V2nRules.size(p, 8, 1)).isEqualTo(1000);
        assertThat(V2nRules.size(p, 8, 0.5)).isEqualTo(500);
        assertThat(V2nRules.size(p.with("x", b -> b.set("fixedSize", 1000.0)), 4, 1)).isEqualTo(1000);
    }

    @Test
    void altGate() {
        assertThat(V2nRules.altState(p, -3.5, 0.6)).isEqualTo(V2nRules.AltState.WEAK);
        assertThat(V2nRules.altState(p, 0.5, 0.2)).isEqualTo(V2nRules.AltState.WEAK);
        assertThat(V2nRules.altState(p, -2, 0.6)).isEqualTo(V2nRules.AltState.SEMI);
        assertThat(V2nRules.altState(p, 1, 0.6)).isEqualTo(V2nRules.AltState.NORMAL);
        assertThat(V2nRules.altState(p.with("x", b -> b.set("altGate", false)), -10, 0)).isEqualTo(V2nRules.AltState.NORMAL);
    }

    @Test
    void trendAndExtension() {
        assertThat(V2nRules.trendOk(10, 9, 8, 9.5)).isTrue();
        assertThat(V2nRules.trendOk(10, 9, 9.5, 9.5)).isFalse();
        assertThat(V2nRules.trendOk(10, 9, 8, Double.NaN)).isFalse();
        assertThat(V2nRules.extension(10.6, 10, 0.2)).isCloseTo(3.0, offset(1e-9));
    }

    @Test
    void priceForNetInvertsNetAt() {
        double x = V2nRules.priceForNet(100, 1001, 0.001, 0.001, 50);
        assertThat(V2nRules.netAt(100, 1001, 0.001, 0.001, x)).isCloseTo(50, offset(1e-9));
    }

    @Test
    void trailTightensAfterFiveR() {
        assertThat(V2nRules.trailStop(p, 100, 2, 1)).isEqualTo(94);
        assertThat(V2nRules.trailStop(p, 100, 2, 6)).isEqualTo(96);
    }
}
