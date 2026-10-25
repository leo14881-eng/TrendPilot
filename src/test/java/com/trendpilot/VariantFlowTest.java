package com.trendpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.job.DiscoveryJob;
import com.trendpilot.job.MonitorJob;
import com.trendpilot.market.Kline;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.Ledger;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.research.CoinGeckoClient;
import com.trendpilot.research.ResearchService;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;
import com.trendpilot.variant.ParamSets;
import com.trendpilot.variant.VariantOverrides;
import com.trendpilot.variant.VariantRegistry;

/**
 * 配置化影子变体：V5（迁移到框架，门槛压到 1%）、V6（每笔 1,500U + H2 提前离场）、V7（H1 追高半仓）、V8（超过最多 3 个活跃，不上线）。
 * 验证各变体独立开仓和平仓、不占 V2 资金、不发开仓 Telegram；V2 没有转正参数时不受影响，转正后按新参数卖出，回滚后恢复基准。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class VariantFlowTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        r.add("trendpilot.variants.list[0].name", () -> "V5");
        r.add("trendpilot.variants.list[0].base", () -> "V2");
        r.add("trendpilot.variants.list[0].trade-usdt", () -> "2000");
        r.add("trendpilot.variants.list[0].overrides.min-buy-rel1h", () -> "1.0");
        r.add("trendpilot.variants.list[0].backtest-passed", () -> "true");
        r.add("trendpilot.variants.list[1].name", () -> "V6");
        r.add("trendpilot.variants.list[1].trade-usdt", () -> "1500");
        r.add("trendpilot.variants.list[1].overrides.early-exit-hours", () -> "1");
        r.add("trendpilot.variants.list[1].overrides.early-exit-max-mfe-pct", () -> "1");
        r.add("trendpilot.variants.list[1].overrides.early-exit-loss-pct", () -> "2");
        r.add("trendpilot.variants.list[2].name", () -> "V7");
        r.add("trendpilot.variants.list[2].overrides.btc24h-above-pct", () -> "-100");
        r.add("trendpilot.variants.list[2].overrides.hot-entry", () -> "HALF");
        r.add("trendpilot.variants.list[3].name", () -> "V8");
        r.add("trendpilot.variants.list[3].overrides.max-volume-accel", () -> "4");
        r.add("trendpilot.variants.list[4].name", () -> "V9");
        r.add("trendpilot.variants.list[4].base", () -> "V1");
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
    }

    @Autowired FakeMarket market;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("bybitMarket") FakeMarket bybit;
    @Autowired DiscoveryJob discovery;
    @Autowired MonitorJob monitor;
    @Autowired ShadowBook v2;
    @Autowired VariantRegistry registry;
    @Autowired ParamSets paramSets;
    @Autowired JdbcClient jdbc;
    @Autowired Markets markets;
    @Autowired ResearchService research;
    @Autowired com.trendpilot.report.HoldingsReport holdings;
    @Autowired com.trendpilot.notify.StatusText status;

    @BeforeEach
    void reset() {
        market.reset();
        bybit.reset();
        markets.invalidate();
        Instant fresh = Instant.parse("2026-12-31T00:00:00Z");
        List<CoinGeckoClient.ExchangeTicker> listed = java.util.stream.Stream.of("ABC", "AAA", "BBB", "CCC", "STKX")
                .map(b -> new CoinGeckoClient.ExchangeTicker(b, "USDT", b.toLowerCase() + "-coin")).toList();
        research.saveIdentity(Venue.BINANCE, listed, fresh);
        research.saveIdentity(Venue.BYBIT, listed, fresh);
        research.saveCategory("tokenized-stock", List.of("stkx-coin"), fresh);
    }

    private Ledger book(String name) {
        return registry.get(name).orElseThrow().book();
    }

    @Test
    void variantsTradeIndependentlyAndV2FollowsOnlyPromotedParameters() {
        // 最多 3 个活跃：V5、V6、V7 上线，V8 不上线；base 不是 V2 的 V9 不登记
        assertThat(registry.active()).extracting(VariantRegistry.Variant::name).containsExactly("V5", "V6", "V7");
        assertThat(registry.get("V8")).isEmpty();
        assertThat(registry.get("V9")).isEmpty();
        assertThat(paramSets.current().isEmpty()).isTrue();

        Instant t0 = Instant.parse("2026-10-14T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        for (String s : new String[]{"AAAUSDT", "BBBUSDT", "CCCUSDT"}) market.put(s, 2.0, TestData.flatQuarters(t0, 10), null);

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.entries()).containsExactly("BUY:ABCUSDT");
        Position p2 = v2.position("ABCUSDT").orElseThrow();
        assertThat(p2.costBasis().doubleValue()).isBetween(1000.0, 1005.0);
        assertThat(book("V5").position("ABCUSDT").orElseThrow().costBasis().doubleValue()).isBetween(2000.0, 2010.0);
        assertThat(book("V6").position("ABCUSDT").orElseThrow().costBasis().doubleValue()).isBetween(1500.0, 1508.0);
        // H1 半仓：BTC 24h 门槛设成 -100%，一定算追高，按 500U 买
        assertThat(book("V7").position("ABCUSDT").orElseThrow().costBasis().doubleValue()).isBetween(500.0, 503.0);
        // V6 有自己的持仓表，V2 资金只算 V2 自己的一笔，只有 V2 发了开仓消息
        assertThat(jdbc.sql("SELECT COUNT(*) FROM v6_position").query(Integer.class).single()).isEqualTo(1);
        assertThat(v2.deployedCost()).isCloseTo(p2.costBasis().doubleValue(), org.assertj.core.data.Offset.offset(1e-6));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key LIKE 'entry:%'").query(Integer.class).single())
                .isEqualTo(1);
        // 再跑一轮不加仓
        discovery.runOnce(t0);
        assertThat(book("V6").position("ABCUSDT").orElseThrow().tranches()).isEqualTo(1);

        // 日报、/status 自动列出变体；Telegram 正文不列变体持仓明细
        String md = holdings.markdown(t0.plusSeconds(60));
        assertThat(md).contains("## 五（续）、其他影子变体与自动优化", "**V6**（运行中", "**V7**（运行中", "V2 参数集：基准",
                "### V6 持仓");
        assertThat(holdings.telegramText(t0.plusSeconds(60))).contains("V6").doesNotContain("V6 持仓");
        assertThat(status.text(t0)).contains("V6 1", "V7 1");

        // 2 小时后跌到 100（约 −3.8%，没到止损线）：只有 V6 按"一直没赚过且亏超 2%"提前离场，V2 / V5 / V7 继续持有
        Instant t1 = t0.plusSeconds(2 * 3600);
        fall(t1, 100);
        MonitorJob.Report m1 = monitor.runOnce(t1);
        assertThat(m1.exits()).isEmpty();
        assertThat(book("V6").positions()).isEmpty();
        assertThat(jdbc.sql("SELECT reason_code FROM shadow_event WHERE book = 'V6' AND type = 'SELL'").query(String.class).single())
                .isEqualTo(VariantOverrides.EARLY_EXIT);
        assertThat(v2.position("ABCUSDT")).isPresent();
        assertThat(book("V5").position("ABCUSDT")).isPresent();
        assertThat(book("V7").position("ABCUSDT")).isPresent();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key LIKE 'exit:%'").query(Integer.class).single())
                .isZero();

        // V6 的参数转正后，V2 读到有效参数（基准 + 转正改动），下一根新 1h 收盘按同一规则离场
        paramSets.promote("V6", registry.get("V6").orElseThrow().overrides(), t1, "测试");
        Instant t2 = t1.plusSeconds(3600);
        fall(t2, 100);
        MonitorJob.Report m2 = monitor.runOnce(t2);
        assertThat(m2.exits()).containsExactly(VariantOverrides.EARLY_EXIT + ":ABCUSDT");
        assertThat(v2.positions()).isEmpty();
        assertThat(paramSets.line()).contains("V6", "回滚");

        // 回滚：恢复基准；再回滚没有可回滚的
        assertThat(paramSets.rollback(t2)).isPresent();
        assertThat(paramSets.current().isEmpty()).isTrue();
        assertThat(paramSets.rollback(t2)).isEmpty();
    }

    /** 让 ABC 从 104 跌到 price：最近 24 小时都在 price 横盘，盘口也挪到 price。 */
    private void fall(Instant t, double price) {
        List<Kline> q = TestData.flatQuarters(t, price);
        market.quarters.put("ABCUSDT", q);
        market.quarters.put("BTCUSDT", TestData.flatQuarters(t, 60000));
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", price, 2, 100, t));
        market.books.put("BTCUSDT", TestData.book("BTCUSDT", 60000, 1, 100, t));
        markets.invalidate();
    }
}
