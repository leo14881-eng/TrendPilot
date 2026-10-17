package com.trendpilot.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.Position;
import com.trendpilot.support.TestData;

/** 上涨逻辑失效但亏损：等反弹的相对高点卖；信号恢复取消；数据不可信时不动作。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DecayGuardTest {

    static final Path dataDir = TestData.dataDir();
    static final long H = 3_600_000L;
    static final Instant T0 = Instant.parse("2026-10-17T00:05:00Z");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired DecayGuard guard;

    static Position pos(String sym, boolean lossRecovery) {
        return new Position(Venue.BINANCE, sym, new BigDecimal("1000"), new BigDecimal("1001"), new BigDecimal("1000"), 1,
                new BigDecimal("1"), T0, "G", Instant.parse("2026-10-15T00:00:00Z"), false, BigDecimal.ZERO, BigDecimal.ZERO, 3,
                lossRecovery);
    }

    static ExitDecision hold(Position p, double net, boolean complete, List<String> notes) {
        Fill fill = new Fill(new BigDecimal("1000"), new BigDecimal("900"), new BigDecimal("0.9"), new BigDecimal("1"), 0, complete);
        return new ExitDecision(p.symbol(), false, "HOLD", p, notes, BigDecimal.valueOf(net), net / 10, fill);
    }

    ExitDecision at(String sym, boolean lr, int hour, double close, double net) {
        Position p = pos(sym, lr);
        return guard.apply("V2", p, hold(p, net, true, List.of()), true, close, hour * H, T0.plus(Duration.ofHours(hour)));
    }

    @Test
    void sellsOnFirstPullbackAfterBounce() {
        assertThat(at("DECAYAUSDT", true, 1, 0.90, -100).sell()).isFalse(); // 开始等待
        assertThat(at("DECAYAUSDT", true, 1, 0.80, -200).sell()).isFalse(); // 同一根 1h 不重复判断
        assertThat(at("DECAYAUSDT", true, 2, 0.88, -120).sell()).isFalse(); // 继续跌
        assertThat(at("DECAYAUSDT", true, 3, 0.91, -90).sell()).isFalse();  // 反弹
        ExitDecision d = at("DECAYAUSDT", true, 4, 0.905, -95);             // 反弹后第一根回落
        assertThat(d.sell()).isTrue();
        assertThat(d.reason()).isEqualTo("DECAY_STOP");
    }

    @Test
    void breakEvenOrTimeoutSellsAndRecoveryCancels() {
        assertThat(at("DECAYBUSDT", true, 1, 0.95, -50).sell()).isFalse();
        assertThat(at("DECAYBUSDT", true, 2, 1.01, 3).sell()).isTrue(); // 回本就卖

        assertThat(at("DECAYCUSDT", true, 1, 0.95, -50).sell()).isFalse();
        assertThat(at("DECAYCUSDT", true, 25, 0.90, -100).sell()).isTrue(); // 等满 24 小时

        assertThat(at("DECAYDUSDT", true, 1, 0.95, -50).sell()).isFalse();
        assertThat(at("DECAYDUSDT", false, 2, 0.96, -40).sell()).isFalse(); // 信号恢复：取消等待
        assertThat(at("DECAYDUSDT", false, 30, 0.90, -100).sell()).isFalse();
    }

    @Test
    void noActionWithoutTrustedData() {
        Position p = pos("DECAYEUSDT", true);
        guard.apply("V2", p, hold(p, -50, true, List.of()), true, 0.95, H, T0.plus(Duration.ofHours(1)));
        Instant t = T0.plus(Duration.ofHours(30));
        assertThat(guard.apply("V2", p, hold(p, -50, false, List.of()), true, 0.9, 30 * H, t).sell()).isFalse();
        assertThat(guard.apply("V2", p, hold(p, -50, true, List.of("ABNORMAL_BOOK_HOLD")), true, 0.9, 30 * H, t).sell()).isFalse();
        assertThat(guard.apply("V2", p, hold(p, -50, true, List.of()), false, 0.9, 30 * H, t).sell()).isFalse();
        assertThat(guard.apply("V2", p, hold(p, -50, true, List.of()), true, Double.NaN, 30 * H, t).sell()).isFalse();
        // 另一个账本的同一持仓状态独立
        assertThat(guard.apply("V2D15", p, hold(p, -50, true, List.of()), true, 0.9, 30 * H, t).sell()).isFalse();
        assertThat(guard.apply("V2", p, hold(p, -50, true, List.of()), true, 0.9, 30 * H, t).sell()).isTrue();
    }
}
