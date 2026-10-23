package com.trendpilot.btctrend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.btctrend.BtcTrendService.Outcome;
import com.trendpilot.btctrend.BtcTrendService.Status;
import com.trendpilot.btctrend.BtcTrendStore.Slot;
import com.trendpilot.market.Kline;
import com.trendpilot.support.TestData;

/**
 * 线上运行路径：逐日运行与参考实现逐笔一致；启动规则；未收盘日线不参与；同一天不重复成交；数据过期不动作；重启后从数据库恢复。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BtcTrendServiceTest {

    static final Path dataDir = TestData.dataDir();
    static final long D = BtcTrendService.DAY_MS;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired BtcTrendStore store;
    @Autowired JdbcClient jdbc;

    /** btc_long.csv 转成日线（开盘时间 = 当天 00:00 UTC）。 */
    static List<Kline> all = toKlines();

    static List<Kline> toKlines() {
        List<Kline> out = new ArrayList<>();
        for (String[] c : MaTrendRulesTest.csv("btc_long.csv")) {
            long open = LocalDate.parse(c[0]).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            out.add(new Kline(open, Double.parseDouble(c[1]), Double.parseDouble(c[2]), Double.parseDouble(c[3]),
                    Double.parseDouble(c[4]), 0, open + D - 1));
        }
        return out;
    }

    static int indexOf(String date) {
        long t = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        for (int i = 0; i < all.size(); i++) if (all.get(i).openTime() == t) return i;
        throw new IllegalArgumentException(date);
    }

    /** 执行日 exec 的 00:05 UTC。 */
    static Instant at(String exec) {
        return LocalDate.parse(exec).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(300);
    }

    /** 交易所在 exec 日 00:05 能返回的日线：到 exec 当天（进行中的那根）为止。 */
    static List<Kline> upTo(String exec) {
        return all.subList(0, indexOf(exec) + 1);
    }

    BtcTrendService service() {
        return new BtcTrendService(store, TrendExecutor.SHADOW);
    }

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM btc_trend_slot").update();
        jdbc.sql("DELETE FROM btc_trend_trade").update();
        jdbc.sql("DELETE FROM btc_trend_meta").update();
    }

    @Test
    void dailyRunsReproduceGoldenTrades() {
        BtcTrendService s = service();
        for (int i = indexOf("2018-08-01"); i < all.size(); i++) {
            String exec = BtcTrendService.day(all.get(i).openTime());
            Outcome o = s.run(all.subList(0, i + 1), at(exec));
            assertThat(o.status()).isEqualTo(Status.DONE);
        }
        List<BtcTrendStore.TradeRow> trades = store.trades();
        List<String[]> golden = MaTrendRulesTest.csv("trades_golden.csv");
        assertThat(trades).hasSize(golden.size());
        for (int i = 0; i < golden.size(); i++) {
            String[] g = golden.get(i);
            BtcTrendStore.TradeRow t = trades.get(i);
            assertThat(t.execDay()).isEqualTo(g[0]);
            assertThat(t.sma()).isEqualTo(Integer.parseInt(g[1]));
            assertThat(t.side()).isEqualTo(g[2]);
            assertThat(t.price()).isCloseTo(Double.parseDouble(g[3]), within(0.01));
            assertThat(t.usdt()).isCloseTo(Double.parseDouble(g[4]), within(0.01));
            assertThat(t.btc()).isCloseTo(Double.parseDouble(g[5]), within(1e-8));
            assertThat(t.mode()).isEqualTo("SHADOW");
        }
        double last = all.getLast().close();
        double total = store.slots().stream().mapToDouble(x -> x.holding() ? x.btc() * last : x.usdt()).sum();
        assertThat(total).isCloseTo(629_797.89, within(629_797.89 * 0.0001));
    }

    @Test
    void startupBuysSlotsAboveBuyLineAndRerunDoesNothing() {
        // 2026-10-23 启动：10-22 收盘 81,307.16 高于 4 条买入线 → 4 份全部按 10-23 开盘价买入
        List<Kline> raw = new ArrayList<>(all);
        long today = all.getLast().openTime() + D;
        raw.add(new Kline(today, 81_307.16, 81_400, 81_000, 81_200, 0, today + D - 1)); // 进行中的那根
        Outcome o = service().run(raw, at("2026-10-23"));
        assertThat(o.status()).isEqualTo(Status.DONE);
        assertThat(o.signalDay()).isEqualTo("2026-10-22");
        assertThat(o.actions()).hasSize(4).allMatch(a -> a.contains("买入"));
        assertThat(store.slots()).allMatch(Slot::holding);
        assertThat(store.trades()).allMatch(t -> t.price() == 81_307.16 && t.usdt() == 5_000);
        assertThat(store.meta("sma75").map(Double::parseDouble).orElseThrow()).isCloseTo(78_471.12, within(0.01));
        assertThat(store.meta("sma150").map(Double::parseDouble).orElseThrow()).isCloseTo(71_412.16, within(0.01));
        assertThat(o.daily()).contains("BTC 均线趋势", "SMA75", "总价值", "一直拿着");

        // 同一天再跑（包括程序重启后用新的实例）：不重复成交
        Outcome again = service().run(raw, at("2026-10-23").plusSeconds(3_600));
        assertThat(again.status()).isEqualTo(Status.ALREADY);
        assertThat(store.trades()).hasSize(4);
    }

    @Test
    void unclosedBarIsIgnored() {
        List<Kline> raw = new ArrayList<>(all);
        long today = all.getLast().openTime() + D;
        // 今天进行中的那根暴跌到 1：如果被当成已收盘，均线和信号都会变
        raw.add(new Kline(today, 81_307.16, 81_400, 1, 1, 0, today + D - 1));
        Outcome o = service().run(raw, at("2026-10-23"));
        assertThat(o.signalDay()).isEqualTo("2026-10-22");
        assertThat(o.actions()).hasSize(4).allMatch(a -> a.contains("买入"));
    }

    @Test
    void staleOrMissingDataDoesNothing() {
        // 10-25 运行但最新已收盘日线还是 10-22：不动作
        Outcome o = service().run(all, at("2026-10-25"));
        assertThat(o.status()).isEqualTo(Status.STALE);
        assertThat(store.trades()).isEmpty();
        assertThat(service().run(List.of(), at("2026-10-25")).status()).isEqualTo(Status.NO_DATA);
        assertThat(store.slots()).isEmpty();
    }

    @Test
    void shortHistoryOnlyTradesSlotsWithEnoughBars() {
        // 只有 100 根已收盘日线：SMA125 / SMA150 不出信号
        int end = indexOf("2026-10-22");
        List<Kline> raw = new ArrayList<>(all.subList(end - 99, end + 1));
        Outcome o = service().run(raw, at("2026-10-23"));
        assertThat(o.status()).isEqualTo(Status.DONE);
        assertThat(store.slots()).filteredOn(Slot::holding).extracting(Slot::sma).containsExactly(75, 100);
        assertThat(store.slots()).filteredOn(x -> !x.holding()).extracting(Slot::sma).containsExactly(125, 150);
    }

    @Test
    void sellsBelowSellLineAtNextOpen() {
        BtcTrendService s = service();
        List<Kline> raw = new ArrayList<>(all);
        long d1 = all.getLast().openTime() + D;
        raw.add(new Kline(d1, 81_307.16, 81_400, 81_000, 81_200, 0, d1 + D - 1));
        s.run(raw, at("2026-10-23"));
        // 10-23 收盘跌到 72,000：低于 SMA75 卖出线（约 76,900）和 SMA100 卖出线（约 73,500），高于 SMA125（约 71,000）和 SMA150（约 70,000）的卖出线
        raw.set(raw.size() - 1, new Kline(d1, 81_307.16, 81_400, 71_000, 72_000, 0, d1 + D - 1));
        long d2 = d1 + D;
        raw.add(new Kline(d2, 72_000, 72_100, 71_900, 72_050, 0, d2 + D - 1));
        Outcome o = s.run(raw, at("2026-10-24"));
        assertThat(o.status()).isEqualTo(Status.DONE);
        assertThat(o.actions()).hasSize(2).allMatch(a -> a.contains("卖出"));
        assertThat(store.slots()).filteredOn(x -> !x.holding()).extracting(Slot::sma).containsExactly(75, 100);
        Slot s75 = store.slots().getFirst();
        double btc = 5_000 / 81_307.16 * 0.999;
        assertThat(s75.usdt()).isCloseTo(btc * 72_000 * 0.999, within(1e-6));
        assertThat(s75.cost()).isZero();
    }
}
