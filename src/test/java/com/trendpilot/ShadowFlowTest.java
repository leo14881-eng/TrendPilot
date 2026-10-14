package com.trendpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.job.DiscoveryJob;
import com.trendpilot.job.MonitorJob;
import com.trendpilot.market.Kline;
import com.trendpilot.market.KlineStore;
import com.trendpilot.market.Markets;
import com.trendpilot.replay.BlindReplay;
import com.trendpilot.replay.MissedReplay;
import com.trendpilot.tracking.ExitReplay;
import com.trendpilot.tracking.ExitRule;
import com.trendpilot.tracking.SignalTracker;
import com.trendpilot.tracking.TrackingReport;
import com.trendpilot.research.CoinGeckoClient;
import com.trendpilot.research.ResearchService;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.ObservationBook;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;

/** 端到端：Discovery 影子买入 → Monitor 启动利润保护 → 回落触发保护卖出。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ShadowFlowTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        r.add("trendpilot.tracking.parallel-rules", () -> "S1_L8,T1_T12_g0");
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
    @Autowired @Qualifier("bybitMarket") FakeMarket bybit;
    @Autowired DiscoveryJob discovery;
    @Autowired com.trendpilot.job.SnapshotJob snapshots;
    @Autowired MonitorJob monitor;
    @Autowired ShadowBook book;
    @Autowired ObservationBook v1Book;
    @Autowired JdbcClient jdbc;
    @Autowired Markets markets;
    @Autowired ResearchService research;
    @Autowired KlineStore klines;
    @Autowired BlindReplay blind;
    @Autowired MissedReplay missed;
    @Autowired SignalTracker tracker;
    @Autowired TrackingReport trackingReport;
    @Autowired ExitReplay exitReplay;

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        market.reset();
        bybit.reset();
        markets.invalidate();
        for (String t : new String[]{"position", "shadow_event", "early_seen", "notification_outbox", "app_state",
                "kline_15m", "v1_position", "replay_outcome", "missed_replay", "signal_log", "signal_episode", "signal_track_bar", "signal_outcome", "signal_pullback", "exit_rule_eval", "variant_trade", "equity_curve", "decision_log"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        // 身份资料：测试标的在 CoinGecko 上唯一对应一个项目（资料时间放在测试截面之后，不算过期）
        Instant fresh = Instant.parse("2026-12-31T00:00:00Z");
        List<CoinGeckoClient.ExchangeTicker> listed = java.util.stream.Stream.of("ABC", "XYZ", "BYO", "WEAK", "AAA", "BBB", "CCC", "STKX")
                .map(b -> new CoinGeckoClient.ExchangeTicker(b, "USDT", b.toLowerCase() + "-coin")).toList();
        research.saveIdentity(Venue.BINANCE, listed, fresh);
        research.saveIdentity(Venue.BYBIT, listed, fresh);
        // 分类资料已加载（没有分类资料时不做新买入）
        research.saveCategory("tokenized-stock", List.of("stkx-coin"), fresh);
    }

    @Test
    void blindAndMissedReplay() {
        Instant t0 = Instant.parse("2026-10-14T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        for (String s : new String[]{"AAAUSDT", "BBBUSDT", "CCCUSDT"}) {
            market.put(s, 2.0, TestData.flatQuarters(t0, 10), null);
        }
        assertThat(discovery.runOnce(t0).entries()).containsExactly("BUY:ABCUSDT");
        double price = jdbc.sql("SELECT price FROM signal_log WHERE symbol = 'ABCUSDT'").query(Double.class).single();

        // 信号之后 1 小时的 15m K 线（WebSocket 写入缓存的那种）
        long q = TestData.Q;
        long start = Instant.parse("2026-10-14T10:15:00Z").toEpochMilli();
        List<Kline> after = java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> new Kline(start + i * q, price, price * 1.06, price, price * 1.05, 1e5, start + (i + 1) * q - 1))
                .toList();
        klines.upsert(Venue.BINANCE, "ABCUSDT", after);

        Instant t1 = t0.plusSeconds(3600);
        assertThat(blind.run(t1)).isEqualTo(1);       // 只有 1h 到期，4h / 24h 还没到
        assertThat(blind.run(t1)).isZero();           // 不重复写
        List<BlindReplay.Bucket> buckets = blind.summary(t1, 7);
        assertThat(buckets).hasSize(1);
        assertThat(buckets.getFirst().avgReturnPct()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(blind.run(t0.plusSeconds(4 * 3600 + 60))).isZero(); // 4h 缺 K 线：跳过，不补造

        List<MissedReplay.Missed> rows = missed.run(t1);
        assertThat(rows).extracting(MissedReplay.Missed::symbol).startsWith("ABCUSDT");
        assertThat(rows.getFirst().outcome()).isEqualTo("V2_BOUGHT");
        assertThat(rows).filteredOn(r -> r.symbol().equals("AAAUSDT")).extracting(MissedReplay.Missed::outcome)
                .containsExactly("NEVER_EARLY");
        String text = jdbc.sql("SELECT text FROM notification_outbox WHERE event_key LIKE 'missed:%'")
                .query(String.class).single();
        assertThat(text).contains("Missed Replay", "ABCUSDT", "V2 已买入", "Blind Replay");

        // 信号跟踪：本轮 ABC 开了 SIGNAL 和 V2_ENTRY 两个样本，起点价取最优价卖一
        var episodes = jdbc.sql("SELECT start_type, p0, status FROM signal_episode WHERE symbol = 'ABCUSDT' ORDER BY start_type")
                .query((rs, i) -> rs.getString(1) + ":" + rs.getString(3)).list();
        assertThat(episodes).containsExactly("SIGNAL:TRACKING", "V2_ENTRY:TRACKING");
        double p0 = jdbc.sql("SELECT p0 FROM signal_episode WHERE symbol = 'ABCUSDT' AND start_type = 'SIGNAL'")
                .query(Double.class).single();
        assertThat(p0).isGreaterThan(price); // 卖一价高于最近收盘价
        // 同一轮重复：不开新样本，只记重复次数
        discovery.runOnce(t0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM signal_episode WHERE symbol = 'ABCUSDT' AND start_type = 'SIGNAL'")
                .query(Integer.class).single()).isEqualTo(1);

        // 之后 80 小时的 15m K 线（缓存里有）：先跌 3%，再涨到 +25%，回落 12%，再创新高
        List<Kline> path = java.util.stream.IntStream.range(0, 320).mapToObj(i -> {
            double c = i < 8 ? price * (1 - 0.03 * i / 8) : i < 40 ? price * (0.97 + 0.28 * (i - 8) / 32)
                    : i < 60 ? price * (1.25 - 0.15 * (i - 40) / 20) : price * (1.10 + 0.2 * (i - 60) / 260);
            return new Kline(start + i * q, c, c * 1.002, c * 0.998, c, 1e5, start + (i + 1) * q - 1);
        }).toList();
        klines.upsert(Venue.BINANCE, "ABCUSDT", path);
        Instant later = t0.plus(java.time.Duration.ofHours(76));
        assertThat(tracker.track(later)).isGreaterThanOrEqualTo(2);
        TrackingReport.Summary sum = trackingReport.summary(later);
        assertThat(sum.signal().gate().show()).isFalse(); // 1 个样本：样本不足，不给结论
        var sample = trackingReport.samples("SIGNAL").stream().filter(x -> x.symbol().equals("ABCUSDT")).findFirst().orElseThrow();
        assertThat(sample.observedMinutes()).isGreaterThan(72 * 60);
        assertThat(sample.m().get("t_big_20")).isNotNull();
        assertThat(sample.m().get("swing_10_recovered")).isEqualTo(true);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM signal_pullback").query(Integer.class).single()).isPositive();
        assertThat(trackingReport.lines(sum, true)).anyMatch(l -> l.contains("样本不足"));

        // 离线回放：B0 + 全部候选规则，每个样本一条
        List<ExitReplay.Trade> trades = exitReplay.run(later);
        int perEpisode = ExitRule.grid().size() + 1;
        assertThat(trades).hasSize(perEpisode * 2);
        var stats = exitReplay.stats(exitReplay.latest());
        assertThat(stats).anyMatch(st -> st.ruleId().equals("B0"));
        assertThat(exitReplay.lines(stats, 3)).anyMatch(l -> l.contains("未达采纳门槛"));
    }

    @Test
    void missingCategoryDataBlocksNewBuys() {
        jdbc.sql("DELETE FROM coin_category").update();
        Instant t0 = Instant.parse("2026-10-14T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        for (String s : new String[]{"AAAUSDT", "BBBUSDT", "CCCUSDT"}) {
            market.put(s, 2.0, TestData.flatQuarters(t0, 10), null);
        }
        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.scanComplete()).isFalse();
        assertThat(d.entries()).isEmpty();
        assertThat(d.pools()).contains("CATEGORY_DATA_MISSING");
        assertThat(book.positions()).isEmpty();
    }

    @Test
    void buyProtectAndSell() {
        Instant t0 = Instant.parse("2026-10-14T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        for (String s : new String[]{"AAAUSDT", "BBBUSDT", "CCCUSDT"}) {
            market.put(s, 2.0, TestData.flatQuarters(t0, 10), null);
        }
        market.put("USDCUSDT", 0.0, TestData.flatQuarters(t0, 1), null); // 稳定币应被排除

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.scanComplete()).isTrue();
        assertThat(d.universe()).isEqualTo(5);
        assertThat(d.entries()).containsExactly("BUY:ABCUSDT");
        // 首次运行：每个标的一次 15m 全量请求，没有 1h 请求
        assertThat(d.klineRequests()).isEqualTo(5);
        assertThat(book.positions()).hasSize(1);

        // 同一 generation 再跑不会重复买入（加仓条件不满足），K 线全部命中缓存，交易对信息也走缓存
        DiscoveryJob.Report again = discovery.runOnce(t0);
        assertThat(again.entries()).isEmpty();
        assertThat(again.klineRequests()).isZero();
        assertThat(market.symbolRequests.get()).isEqualTo(1);

        // 价格上涨 3%：启动利润保护
        Instant t1 = t0.plusSeconds(300);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 107.2, 2, 100, t1));
        markets.invalidate(); // 模拟 5 分钟后盘口缓存已过期
        int klinesBefore = market.klineRequests.get();
        MonitorJob.Report m1 = monitor.runOnce(t1);
        assertThat(m1.exits()).isEmpty();
        assertThat(market.klineRequests.get()).isEqualTo(klinesBefore); // 没有新收盘的 15m K 线，不请求
        assertThat(book.positions().getFirst().protectArmed()).isTrue();

        // 回落到底线以下但仍有净利：保护卖出
        Instant t2 = t1.plusSeconds(300);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 104.7, 2, 100, t2));
        markets.invalidate();
        MonitorJob.Report m2 = monitor.runOnce(t2);
        assertThat(m2.exits()).containsExactly("PROFIT_PROTECTION:ABCUSDT");
        assertThat(book.positions()).isEmpty();
        assertThat(book.realizedPnl()).isPositive();
        // 每笔卖出记录最大浮盈、最大浮亏、首次转盈利用时（第一次 Monitor 就有净利：5 分钟）
        var ex = jdbc.sql("SELECT max_net, min_net, minutes_to_profit, pnl_usdt FROM shadow_event WHERE book = 'V2' AND type = 'SELL'")
                .query((rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), Double.parseDouble(rs.getString(4))})
                .single();
        assertThat(ex[0]).isGreaterThan(ex[3]);
        assertThat(ex[1]).isLessThanOrEqualTo(ex[3]);
        assertThat(ex[2]).isEqualTo(5.0);
        // 候选规则影子并行：没触发提前卖出的变体随 V2 一起卖出，并记录 V2 的结果用于配对
        var variants = jdbc.sql("SELECT rule_id, status, exit_reason, b0_pnl FROM variant_trade ORDER BY rule_id")
                .query((rs, i) -> rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getString(3) + ":" + (rs.getObject(4) != null))
                .list();
        assertThat(variants).containsExactly("S1_L8:CLOSED:PROFIT_PROTECTION:true", "T1_T12_g0:CLOSED:PROFIT_PROTECTION:true");

        Integer events = jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE book = 'V2' AND type IN ('BUY','SELL','PROTECT_ARM')")
                .query(Integer.class).single();
        assertThat(events).isEqualTo(3);
        Integer outbox = jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE status = 'PREPARED'")
                .query(Integer.class).single();
        assertThat(outbox).isGreaterThanOrEqualTo(3);
    }

    @Test
    void monitorSkipsOnlyThePositionWithMissingBook() {
        Instant t0 = Instant.parse("2026-10-15T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("XYZUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("XYZUSDT", 104, 2, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        discovery.runOnce(t0);
        assertThat(book.position("XYZUSDT")).isPresent();
        assertThat(book.position("ABCUSDT")).isPresent();

        // XYZ 拿不到盘口：只跳过 XYZ，ABC 照常管理（涨 3% 启动利润保护）
        market.books.remove("XYZUSDT");
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 107.2, 2, 100, t0.plusSeconds(300)));
        markets.invalidate();
        MonitorJob.Report m = monitor.runOnce(t0.plusSeconds(300));
        assertThat(m.dataStatus()).contains("SKIPPED=[XYZUSDT]");
        assertThat(book.position("XYZUSDT")).isPresent();
        assertThat(book.position("ABCUSDT").orElseThrow().protectArmed()).isTrue();

        // 下架且无盘口：不卖出、冻结在账上，只记一次 DELISTED_UNPRICED
        var info = market.symbols.get("XYZUSDT");
        market.symbols.put("XYZUSDT", new com.trendpilot.market.SymbolInfo(info.symbol(), info.baseAsset(),
                info.quoteAsset(), false, info.tags()));
        markets.invalidate();
        MonitorJob.Report d1 = monitor.runOnce(t0.plusSeconds(600));
        monitor.runOnce(t0.plusSeconds(900));
        assertThat(d1.dataStatus()).contains("DELISTED_UNPRICED=[XYZUSDT]");
        assertThat(book.position("XYZUSDT")).isPresent();
        Integer notes = jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE type = 'DELISTED_UNPRICED'")
                .query(Integer.class).single();
        assertThat(notes).isEqualTo(1);
    }

    @Test
    void bybitPoolOnlyCoversSymbolsMissingOnBinanceAndUsesItsOwnBtc() {
        Instant t0 = Instant.parse("2026-10-16T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 1.0, TestData.flatQuarters(t0, 10), null);
        bybit.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        // ABC 两边都有：只用 Binance（Binance 上没有信号），Bybit 上的强势不算
        bybit.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        // BYO 只在 Bybit：进入 Bybit 独立池，用 Bybit 的盘口影子买入
        bybit.put("BYOUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("BYOUSDT", 104, 2, 100, t0));

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.entries()).containsExactly("BUY:BYOUSDT");
        assertThat(book.position("BYOUSDT").orElseThrow().venue()).isEqualTo(Venue.BYBIT);
        assertThat(d.pools()).contains("BINANCE=2/COMPLETE", "BYBIT=2/COMPLETE");

        // Monitor 用 Bybit 的盘口管理这个持仓
        bybit.books.put("BYOUSDT", TestData.book("BYOUSDT", 107.2, 2, 100, t0.plusSeconds(300)));
        markets.invalidate();
        monitor.runOnce(t0.plusSeconds(300));
        assertThat(book.position("BYOUSDT").orElseThrow().protectArmed()).isTrue();
    }

    @Test
    void bybitWithoutItsOwnBtcProducesNoSignals() {
        Instant t0 = Instant.parse("2026-10-17T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        bybit.put("BYOUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("BYOUSDT", 104, 2, 100, t0));

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.entries()).isEmpty();
        assertThat(d.early()).isZero();
        assertThat(d.pools()).contains("BYBIT=1/PARTIAL_DATA");
    }

    @Test
    void earlyBelowScoreEightGoesToV1OnlyAndExitsByReferencePrice() {
        Instant t0 = Instant.parse("2026-10-18T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        market.put("WEAKUSDT", 1.0, TestData.flatQuarters(t0, 10), null);

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.v1Entries()).contains("ABCUSDT");
        Position v1 = v1Book.position("ABCUSDT").orElseThrow();
        assertThat(v1.costBasis().doubleValue()).isEqualTo(1001.0, org.assertj.core.data.Offset.offset(0.01));
        String v1Note = jdbc.sql("SELECT text FROM notification_outbox WHERE event_key LIKE 'v1-entry:%'")
                .query(String.class).single();
        assertThat(v1Note).startsWith("🟢 V1 观察买入").contains("ABCUSDT");
        // V1 不占用 V2 资金
        assertThat(book.deployedCost()).isEqualTo(book.positions().stream()
                .mapToDouble(p -> p.costBasis().doubleValue()).sum());

        // 参考价涨 3% 后回落到保护底线以下：V1 按参考价保护卖出
        Ticker24h up = market.tickers.get("ABCUSDT");
        market.tickers.put("ABCUSDT", new Ticker24h("ABCUSDT", up.lastPrice() * 1.03, 5, 1e7));
        markets.invalidate(); // 模拟行情缓存已过期
        monitor.runOnce(t0.plusSeconds(300));
        assertThat(v1Book.position("ABCUSDT").orElseThrow().protectArmed()).isTrue();
        market.tickers.put("ABCUSDT", new Ticker24h("ABCUSDT", up.lastPrice() * 1.007, 5, 1e7));
        markets.invalidate();
        monitor.runOnce(t0.plusSeconds(600));
        assertThat(v1Book.position("ABCUSDT")).isEmpty();
        assertThat(v1Book.realizedPnl()).isPositive();
        var stats = v1Book.stats();
        assertThat(stats.closed()).isEqualTo(1);
        assertThat(stats.winRatePct()).isEqualTo(100);
        assertThat(stats.avgHoldHours()).isEqualTo(10 / 60.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(stats.line()).startsWith("V1：买入 1 笔，平仓 1 笔，胜率 100%");
    }

    @Test
    void unverifiedIdentityBlocksV2ButNotV1AndResearchIsRecorded() {
        Instant t0 = Instant.parse("2026-10-19T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("NEWUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("NEWUSDT", 104, 2, 100, t0));

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.entries()).isEmpty();
        assertThat(d.v1Entries()).contains("NEWUSDT");
        String reasons = jdbc.sql("SELECT reasons FROM decision_log WHERE symbol = 'NEWUSDT'").query(String.class).single();
        assertThat(reasons).contains("IDENTITY_UNVERIFIED");
        String identity = jdbc.sql("SELECT identity FROM research_record WHERE symbol = 'NEWUSDT' ORDER BY id DESC LIMIT 1")
                .query(String.class).single();
        assertThat(identity).isEqualTo("UNVERIFIED");
    }

    @Test
    void dailySnapshotHasStatsAndNoSecrets() throws Exception {
        Instant t0 = Instant.parse("2026-10-19T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        discovery.runOnce(t0);
        java.nio.file.Path file = snapshots.write(t0);
        String text = java.nio.file.Files.readString(file);
        assertThat(text).contains("\"books\"", "\"tracking\"", "\"decisions7d\"", "\"exitReplay\"")
                .doesNotContainIgnoringCase("token");
    }

    @Test
    void fastConfirmSellsImmediatelyWithoutWaitingForMonitor() {
        Instant t0 = Instant.parse("2026-10-20T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        discovery.runOnce(t0);
        Instant t1 = t0.plusSeconds(300);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 107.2, 2, 100, t1));
        markets.invalidate();
        monitor.runOnce(t1);
        assertThat(book.positions().getFirst().protectArmed()).isTrue();

        // 1 分钟后（不到下一轮 5 分钟检查）跌破底线：快速确认重新取盘口，立即卖出
        Instant t2 = t1.plusSeconds(60);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 104.7, 2, 100, t2)); // 盘口缓存里还是旧的 107.2
        MonitorJob.Report fast = monitor.confirmNow(java.util.Set.of("ABCUSDT"), t2);
        assertThat(fast.exits()).containsExactly("PROFIT_PROTECTION:ABCUSDT");
        assertThat(book.positions()).isEmpty();
    }
}
