package com.trendpilot.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.market.Ticker24h;
import com.trendpilot.support.TestData;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SystemRiskTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired
    SystemRiskAssessor assessor;

    @Autowired
    RiskRecovery recovery;

    static final Instant NOW = Instant.parse("2026-10-18T10:17:00Z");
    static final List<Ticker24h> UNIVERSE = List.of(new Ticker24h("AUSDT", 1, 2, 1e7), new Ticker24h("BUSDT", 1, 1, 1e7));
    static final Ticker24h BTC = new Ticker24h("BTCUSDT", 100_000, 1, 1e9);

    private RiskState assess(MarketEvidence e) {
        return assessor.assess(TestData.flat("BTCUSDT", NOW, 100_000), BTC, UNIVERSE, 0, e);
    }

    @Test
    void evidenceGates() {
        assertThat(assess(new MarketEvidence(5, 0.01, 0)).newRiskAllowed()).isTrue();
        assertThat(assess(new MarketEvidence(Double.NaN, 0.01, 0)).reasons()).contains("LIQUIDITY_EVIDENCE_MISSING");
        assertThat(assess(new MarketEvidence(40, 0.01, 0)).reasons()).contains("LIQUIDITY_STRESS");
        assertThat(assess(new MarketEvidence(5, Double.NaN, 0)).reasons()).contains("STABLECOIN_EVIDENCE_MISSING");
        RiskState depeg = assess(new MarketEvidence(5, 0.8, 0));
        assertThat(depeg.newRiskAllowed()).isFalse();
        assertThat(depeg.reasons()).contains("STABLECOIN_DEPEG");
        // 已确认的市场冲击计入亏损冻结
        assertThat(assess(new MarketEvidence(5, 0.01, 2)).reasons()).contains("LOSS_HALF_BUDGET");
        assertThat(assess(new MarketEvidence(5, 0.01, 3)).reasons()).contains("LOSS_FREEZE");
    }

    @Test
    void stagedRecoveryAndShockRecord() {
        RiskState blocked = assess(new MarketEvidence(40, 0.01, 0));
        RiskState ok = assess(new MarketEvidence(5, 0.01, 0));

        assertThat(recovery.apply(blocked, "G0").newRiskAllowed()).isFalse();
        RiskState r1 = recovery.apply(ok, "G1");
        assertThat(r1.newRiskAllowed()).isFalse();
        assertThat(recovery.apply(ok, "G1").newRiskAllowed()).isFalse(); // 同一轮不重复计数
        RiskState r2 = recovery.apply(ok, "G2");
        assertThat(r2.newRiskAllowed()).isTrue();
        assertThat(r2.budgetFactor()).isEqualTo(0.5);
        assertThat(recovery.apply(ok, "G3").budgetFactor()).isEqualTo(0.5);
        assertThat(recovery.apply(ok, "G4").budgetFactor()).isEqualTo(1.0);
        assertThat(recovery.apply(ok, "G5").reasons()).isEmpty();

        RiskState shock = new RiskState(Regime.NEUTRAL, false, 0, 0, 0, -4, 0.5, 0, List.of("BTC_SHOCK_1H"));
        recovery.recordShockIfAny(shock, NOW);
        recovery.recordShockIfAny(ok, NOW.plus(Duration.ofHours(1)));
        assertThat(recovery.confirmedShocks(NOW.plus(Duration.ofHours(2)))).isEqualTo(1);
        assertThat(recovery.confirmedShocks(NOW.plus(Duration.ofHours(25)))).isZero();
        // 数据缺失不算冲击
        recovery.recordShockIfAny(new RiskState(Regime.RISK_OFF, false, 0, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, 0, List.of("BTC_OR_BREADTH_MISSING")), NOW);
        assertThat(recovery.confirmedShocks(NOW.plus(Duration.ofHours(2)))).isEqualTo(1);
    }
}
