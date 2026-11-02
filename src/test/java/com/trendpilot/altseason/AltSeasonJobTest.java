package com.trendpilot.altseason;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.job.AppState;
import com.trendpilot.support.TestData;

/** Alt-season reminder: one row per day, one Telegram message per state change, nothing when the state stays. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AltSeasonJobTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired AltSeasonJob job;
    @Autowired AppState state;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM alt_season_daily").update();
        jdbc.sql("DELETE FROM notification_outbox WHERE event_key LIKE 'altseason:%'").update();
        jdbc.sql("DELETE FROM app_state WHERE key LIKE 'altseason:%'").update();
        state.put("regime", "CONSTRUCTIVE");
    }

    private static AltSeasonRules.Metrics strong() {
        Map<String, double[]> pool = new LinkedHashMap<>();
        for (int i = 0; i < 60; i++) pool.put("C" + i, AltSeasonRulesTest.line(210, 1 + i, 1.0));
        return AltSeasonRules.compute(pool, AltSeasonRulesTest.line(210, 50_000, 0.1), AltSeasonRulesTest.line(210, 3_000, 0.4));
    }

    private int alerts() {
        return jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key LIKE 'altseason:%'").query(Integer.class).single();
    }

    @Test
    void alertsOnceWhenEnteringAndNotWhileTheStateStays() {
        LocalDate d0 = LocalDate.parse("2026-11-01");
        for (int i = 0; i < 16; i++) job.record(d0.plusDays(i), strong(), Instant.parse("2026-11-02T00:07:00Z").plusSeconds(86_400L * i));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM alt_season_daily").query(Integer.class).single()).isEqualTo(16);
        // 14 consecutive days of beating BTC are needed: day 14 enters, later days stay EARLY without new messages
        assertThat(jdbc.sql("SELECT state FROM alt_season_daily ORDER BY day").query(String.class).list())
                .startsWith("OFF", "OFF").contains("EARLY").endsWith("EARLY", "EARLY");
        assertThat(alerts()).isEqualTo(1);
        assertThat(job.latestLine()).hasValueSatisfying(l -> assertThat(l).contains("山寨季早期", "50 日线上方 100%"));
        // thin data: no judgement, state unchanged, no message
        job.record(d0.plusDays(16), new AltSeasonRules.Metrics(10, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false),
                Instant.parse("2026-11-18T00:07:00Z"));
        assertThat(state.get(AltSeasonJob.STATE)).contains("EARLY");
        assertThat(alerts()).isEqualTo(1);
    }
}
