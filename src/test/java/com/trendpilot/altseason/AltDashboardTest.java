package com.trendpilot.altseason;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.trendpilot.market.Kline;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.research.ResearchDataException;
import com.trendpilot.research.StablecoinClient;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;

/** Alt-season dashboard: read-only daily section; any missing source shows "-" and the section is still produced. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AltDashboardTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.altseason.enabled", () -> "false");
    }

    /** Stablecoin source the tests control: fails until data is set; counts requests. */
    static final class FakeStable extends StablecoinClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile Map<Long, Double> data;

        FakeStable() {
            super(RestClient.builder(), "http://unused.test");
        }

        @Override
        public Map<Long, Double> totals() {
            calls.incrementAndGet();
            if (data == null) throw new ResearchDataException("DefiLlama 稳定币接口返回 429");
            return data;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeMarket fakeMarket() {
            return new FakeMarket();
        }

        @Bean
        FakeMarket bybitMarket() {
            return new FakeMarket(Venue.BYBIT);
        }

        @Bean
        @Primary
        FakeStable fakeStable() {
            return new FakeStable();
        }
    }

    @Autowired FakeMarket market;
    @Autowired @Qualifier("bybitMarket") FakeMarket bybit;
    @Autowired FakeStable stable;
    @Autowired AltDashboard dashboard;
    @Autowired AltSeasonJob job;
    @Autowired JdbcClient jdbc;
    @Autowired com.trendpilot.market.Markets markets;

    static final LocalDate LAST = AltEarlySample.START.plusDays(AltEarlySample.DAYS - 1); // 2026-02-04
    static final Instant NOW = LAST.plusDays(1).atTime(0, 7).toInstant(java.time.ZoneOffset.UTC);

    @BeforeEach
    void reset() {
        market.reset();
        bybit.reset();
        stable.calls.set(0);
        stable.data = null;
        for (String t : List.of("alt_dashboard_daily", "stablecoin_supply", "coin_sector", "alt_season_daily")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        jdbc.sql("DELETE FROM coin_identity WHERE coin_id LIKE 'sample-%'").update();
        jdbc.sql("DELETE FROM app_state WHERE key LIKE 'altdash:%'").update();
        jdbc.sql("DELETE FROM notification_outbox WHERE event_key LIKE 'altdash:%'").update();
    }

    private int messages() {
        return jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key LIKE 'altdash:%'").query(Integer.class).single();
    }

    /** Sample bars (last 201 days ending LAST) as closed daily candles. */
    static Map<String, List<Kline>> sampleBars(int days) {
        Map<String, List<Kline>> out = new LinkedHashMap<>();
        int from = AltEarlySample.DAYS - days;
        for (var e : AltEarlySample.market().entrySet()) {
            List<Kline> k = new ArrayList<>();
            for (int d = from; d < AltEarlySample.DAYS; d++) {
                double c = e.getValue()[0][d];
                if (!(c > 0)) continue;
                long open = AltEarlySample.START.plusDays(d).toEpochDay() * 86_400_000L;
                k.add(new Kline(open, c, c * 1.01, c * 0.99, c, e.getValue()[1][d], open + 86_400_000L - 1));
            }
            if (!k.isEmpty()) out.put(e.getKey(), k);
        }
        return out;
    }

    @Test
    void everySourceMissingShowsDashesAndStillProducesTheSection() {
        Optional<String> body = dashboard.run(LAST, Map.of(), Optional.empty(), NOW);
        assertThat(body).isPresent();
        String b = body.get();
        assertThat(b).contains("1. 现有山寨季指标：-", "2. 5 个前兆指标：-", "3. S2 开关：-", "   - Binance：-", "   - Bybit：-", "历史上误报 7–8 成，仅供观察");
        for (String n : AltEarlySignals.NAMES) assertThat(b).contains(n + "：-");
        assertThat(b.lines().filter(l -> l.startsWith("5. ")).count()).isEqualTo(1);
        assertThat(b).endsWith("   - -\n"); // sectors
        assertThat(jdbc.sql("SELECT COUNT(*) FROM alt_dashboard_daily").query(Integer.class).single()).isEqualTo(1);
        assertThat(messages()).isEqualTo(1);
        // the same day is not produced or sent twice
        assertThat(dashboard.run(LAST, Map.of(), Optional.empty(), NOW.plusSeconds(60))).isEmpty();
        assertThat(messages()).isEqualTo(1);
        assertThat(dashboard.latest()).contains(b);
    }

    @Test
    void theDailyJobProducesTheDashboardEvenWithoutAnyMarketData() {
        job.run(NOW);
        assertThat(dashboard.latest()).hasValueSatisfying(b -> assertThat(b).contains("3. S2 开关：-"));
    }

    @Test
    void fullDataShowsTheSharedIndicatorsRotationForBothVenuesAndSectors() {
        stable.data = AltEarlySample.stable();
        Map<String, List<Kline>> bars = sampleBars(AltSeasonJob.DAYS);
        // Bybit: same coins with tickers so the universe filter picks them
        for (var e : sampleBars(AltDashboard.BYBIT_DAYS + 1).entrySet()) {
            String s = e.getKey();
            bybit.symbols.put(s, new SymbolInfo(s, s.replace("USDT", ""), "USDT", true, ""));
            bybit.tickers.put(s, new Ticker24h(s, e.getValue().getLast().close(), 0, e.getValue().getLast().quoteVolume()));
            bybit.daily.put(s, e.getValue());
        }
        markets.invalidate(); // symbol lists are cached
        // sectors for a few sample coins
        String[][] sec = {{"K27USDT", "artificial-intelligence"}, {"K12USDT", "artificial-intelligence"}, {"K17USDT", "artificial-intelligence"},
                {"K01USDT", "meme-token"}, {"K02USDT", "meme-token"}, {"K03USDT", "meme-token"},
                {"K04USDT", "layer-1"}, {"K05USDT", "layer-1"}, {"K06USDT", "layer-1"}, {"K08USDT", "gaming"}, {"K09USDT", "gaming"}};
        for (String[] x : sec) {
            String id = "sample-" + x[0].toLowerCase();
            jdbc.sql("INSERT OR IGNORE INTO coin_identity(venue, symbol, coin_id, updated_at) VALUES ('BINANCE', :s, :c, 't')")
                    .param("s", x[0]).param("c", id).update();
            jdbc.sql("INSERT INTO coin_sector(coin_id, sector, updated_at) VALUES (:c, :g, 't')").param("c", id).param("g", x[1]).update();
        }

        String b = dashboard.run(LAST, bars, Optional.of("2026-02-04 非山寨季；示例"), NOW).orElseThrow();

        // same reading as the shared indicators on the full sample (research values for day 399: a and c on, S2 on)
        assertThat(b).contains("1. 现有山寨季指标：2026-02-04 非山寨季；示例");
        assertThat(b).contains("2. 5 个前兆指标：亮 2 / 5（埋伏第一档要 ≥ 2 个同时亮）");
        assertThat(b).contains("a ETH/BTC 转强：亮", "b 山寨广度修复：不亮", "c 篮子/BTC 60 日新高：亮", "d 稳定币 30 日 > 3%：不亮", "e BTC 新高后横盘：不亮");
        assertThat(b).contains("3. S2 开关：开");
        assertThat(b).contains("   - Binance：K27（21 日 ");
        assertThat(b.lines().filter(l -> l.startsWith("   - Binance：")).findFirst().orElseThrow())
                .contains("K27", "K12", "K17", "K38", "K32");
        assertThat(b.lines().filter(l -> l.startsWith("   - Bybit：")).findFirst().orElseThrow()).doesNotEndWith("-").contains("K27");
        List<String> sectors = b.lines().dropWhile(l -> !l.startsWith("5. ")).skip(1).toList();
        assertThat(sectors).hasSize(3).allSatisfy(l -> assertThat(l).contains("个币）：7 日 ", "龙头 "));
        assertThat(sectors).noneMatch(l -> l.contains("游戏")); // only 2 coins: below the 3-coin minimum
        assertThat(jdbc.sql("SELECT lit FROM alt_dashboard_daily").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT s2 FROM alt_dashboard_daily").query(Integer.class).single()).isEqualTo(1);
        assertThat(stable.calls.get()).isEqualTo(1);
    }

    @Autowired com.trendpilot.research.ResearchService research;

    @Test
    void sectorListsAreKeptWhenCoinGeckoReturnsNothingAndNeverExcludeCoins() {
        Instant t = Instant.parse("2026-11-08T00:00:00Z");
        jdbc.sql("INSERT OR IGNORE INTO coin_identity(venue, symbol, coin_id, updated_at) VALUES ('BINANCE', 'K01USDT', 'sample-k01', 't')").update();
        research.saveSector("artificial-intelligence", List.of("sample-k01"), t);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> research.saveSector("artificial-intelligence", List.of(), t))
                .isInstanceOf(ResearchDataException.class);
        assertThat(research.sectorsBySymbol(Venue.BINANCE)).containsEntry("K01USDT", java.util.Set.of("artificial-intelligence"));
        assertThat(research.excludedSymbols(Venue.BINANCE)).doesNotContain("K01USDT");
    }

    @Test
    void stablecoinsAreRequestedAtMostTwiceADayAndCached() {
        // failure (e.g. 429): no more requests that UTC day
        assertThat(dashboard.stableSeries(LAST, NOW)).isEmpty();
        assertThat(dashboard.stableSeries(LAST, NOW.plusSeconds(3600))).isEmpty();
        assertThat(stable.calls.get()).isEqualTo(1);
        // next day it works and is stored; later calls that day read SQLite only
        stable.data = AltEarlySample.stable();
        Instant tomorrow = NOW.plusSeconds(86_400);
        assertThat(dashboard.stableSeries(LAST, tomorrow)).isNotEmpty();
        assertThat(dashboard.stableSeries(LAST, tomorrow.plusSeconds(60))).isNotEmpty();
        assertThat(stable.calls.get()).isEqualTo(2);
    }
}
