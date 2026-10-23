package com.trendpilot.btctrend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trendpilot.btctrend.MaTrendRules.Action;
import com.trendpilot.btctrend.MaTrendRules.Bar;
import com.trendpilot.btctrend.MaTrendRules.Trade;

/** 对账：与用户交接的参考实现 trend_ref.py 的回测结果（trades_golden.csv）逐笔一致。 */
class MaTrendRulesTest {

    static List<String[]> csv(String name) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                MaTrendRulesTest.class.getResourceAsStream("/btctrend/" + name), StandardCharsets.UTF_8))) {
            r.readLine(); // 表头
            for (String line; (line = r.readLine()) != null; ) {
                line = line.strip(); // Windows 检出时可能带 \r
                if (!line.isEmpty()) rows.add(line.split(","));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    static List<Bar> bars() {
        return csv("btc_long.csv").stream()
                .map(c -> new Bar(c[0], Double.parseDouble(c[1]), Double.parseDouble(c[4]))).toList();
    }

    @Test
    void backtestMatchesGoldenTradeByTrade() {
        MaTrendRules.Result r = MaTrendRules.backtest(bars(), "2018-08-01", 20_000);
        List<String[]> golden = csv("trades_golden.csv");
        assertThat(r.trades()).hasSize(170).hasSize(golden.size());
        for (int i = 0; i < golden.size(); i++) {
            String[] g = golden.get(i);
            Trade t = r.trades().get(i);
            assertThat(t.execDate()).as("第 %d 笔日期", i + 1).isEqualTo(g[0]);
            assertThat(t.sma()).as("第 %d 笔均线", i + 1).isEqualTo(Integer.parseInt(g[1]));
            assertThat(t.side().name()).as("第 %d 笔方向", i + 1).isEqualTo(g[2]);
            assertThat(t.price()).as("第 %d 笔价格", i + 1).isCloseTo(Double.parseDouble(g[3]), within(0.01));
            assertThat(t.usdt()).as("第 %d 笔 USDT", i + 1).isCloseTo(Double.parseDouble(g[4]), within(0.01));
            assertThat(t.btc()).as("第 %d 笔 BTC", i + 1).isCloseTo(Double.parseDouble(g[5]), within(1e-8));
        }
        assertThat(r.finalValue()).isCloseTo(629_797.89, within(629_797.89 * 0.0001));
        assertThat(Math.round(r.maxDrawdown() * 1000) / 10.0).isEqualTo(-43.8);
        assertThat(r.holding()).containsExactly(true, true, true, true);
    }

    @Test
    void notEnoughHistoryGivesNoSignal() {
        double[] closes = new double[149];
        java.util.Arrays.fill(closes, 100);
        closes[148] = 1_000; // 远高于均线
        assertThat(MaTrendRules.signal(closes, 148, 150, false)).isEqualTo(Action.HOLD);
        assertThat(MaTrendRules.signal(closes, 148, 75, false)).isEqualTo(Action.BUY);
        assertThat(MaTrendRules.sma(closes, 148, 150)).isNull();
    }

    @Test
    void insideBandDoesNothing() {
        double[] closes = new double[75];
        java.util.Arrays.fill(closes, 100);
        closes[74] = 101; // 均线约 100.01，收盘在 ±2% 之内
        assertThat(MaTrendRules.signal(closes, 74, 75, false)).isEqualTo(Action.HOLD);
        assertThat(MaTrendRules.signal(closes, 74, 75, true)).isEqualTo(Action.HOLD);
        closes[74] = 90;
        assertThat(MaTrendRules.signal(closes, 74, 75, true)).isEqualTo(Action.SELL);
    }
}
