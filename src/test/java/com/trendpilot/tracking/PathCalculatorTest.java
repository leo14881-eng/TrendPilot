package com.trendpilot.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PathCalculatorTest {

    static final long Q = 15 * 60_000L;
    static final long T0 = 1_000 * Q + 2 * 60_000; // 某根 15m K 线开始后 2 分钟
    static final TrackingProperties P = new TrackingProperties(true, 24, 72, 168, List.of(5.0, 10.0, 20.0, 30.0), 10,
            List.of(5.0, 10.0, 15.0, 20.0), 10, 3, 5, 2, 0.05, 30, 10, 7, 100, 20, 4, List.of());
    static final PathCalculator.Cost COST = new PathCalculator.Cost(100, 0.001, 0, 0);

    /** 第 i 根（从包含 t0 的那根开始）收盘价序列，高低点 = 收盘 ± 0.5%。 */
    static List<PathCalculator.Bar> bars(double... closes) {
        List<PathCalculator.Bar> out = new ArrayList<>();
        long firstClose = (T0 / Q + 1) * Q;
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            out.add(new PathCalculator.Bar(firstClose + i * Q, c, c * 1.005, c * 0.995, c));
        }
        return out;
    }

    @Test
    void netReturnIncludesBothFees() {
        assertThat(COST.net(100)).isCloseTo(-0.2, offset(1e-3));
        assertThat(COST.net(COST.priceAtNet(10))).isCloseTo(10, offset(1e-9));
    }

    @Test
    void dipThenRallyThenPullback() {
        // 先跌到 90，再涨到 125，然后回落 12% 以上，再创新高
        double[] path = new double[300];
        for (int i = 0; i < path.length; i++) {
            if (i < 8) path[i] = 100 - i * 1.25;            // → 91.25
            else if (i < 40) path[i] = 90 + (i - 8) * 35.0 / 32; // → ~124
            else if (i < 60) path[i] = 125 - (i - 40) * 1.0;     // → 106
            else path[i] = 106 + (i - 60) * 0.2;                 // 慢慢创新高
        }
        PathCalculator.Result r = PathCalculator.compute(T0, COST, 2.0, bars(path), false, P);
        assertThat(r.dataGap()).isFalse();
        assertThat(r.minutes("t_profit")).isNotNull();
        assertThat(r.minutes("t_big_10")).isGreaterThan(r.minutes("t_profit"));
        assertThat(r.minutes("t_big_20")).isGreaterThan(r.minutes("t_big_10"));
        assertThat(r.value("mae_before_profit")).isLessThan(-8);
        // 回撤 10% 确认第一个波段高点，之后收复
        assertThat(r.minutes("swing_10_confirm_time")).isGreaterThan(r.minutes("swing_10_time"));
        assertThat(r.value("swing_10_gain")).isGreaterThan(20);
        assertThat((Boolean) r.metrics().get("swing_10_recovered")).isTrue();
        assertThat(r.value("swing_10_depth")).isGreaterThan(10);
        assertThat(r.pullbacks()).isNotEmpty();
        assertThat(r.pullbacks().getFirst().depthPct()).isGreaterThan(10);
        assertThat(r.pullbacks().getFirst().recovered()).isTrue();
        assertThat((Boolean) r.metrics().get("loss_8_recovered")).isTrue();
        assertThat(r.metrics().get("loss_15_recovered")).isNull(); // 没跌到 −15%
        // 固定时点：第 4 根（1 小时后的那根）收盘
        assertThat(r.value("ret_1h")).isCloseTo(COST.net(path[4]), offset(1e-9));
        assertThat(r.observedMinutes()).isEqualTo((int) ((bars(path).getLast().closeTime() - T0) / 60_000));
    }

    @Test
    void firstBarUsesCloseOnlyAndGapsCensor() {
        // 包含 t0 的那根高点 130 不算（可能发生在 t0 之前）
        List<PathCalculator.Bar> b = new ArrayList<>(bars(100, 100, 100));
        PathCalculator.Bar first = b.getFirst();
        b.set(0, new PathCalculator.Bar(first.closeTime(), 100, 130, 99, 100));
        PathCalculator.Result r = PathCalculator.compute(T0, COST, 2.0, b, false, P);
        assertThat(r.value("mfe_72h")).isLessThan(1);

        // 中间缺 3 根：缺口之后不再计算，记为 DATA_GAP
        List<PathCalculator.Bar> gap = new ArrayList<>(bars(100, 101, 102));
        long last = gap.getLast().closeTime();
        gap.add(new PathCalculator.Bar(last + 4 * Q, 150, 150, 150, 150));
        PathCalculator.Result g = PathCalculator.compute(T0, COST, 2.0, gap, false, P);
        assertThat(g.dataGap()).isTrue();
        assertThat(g.value("mfe_7d")).isLessThan(5);
    }

    @Test
    void kaplanMeierHandlesCensoring() {
        // 4 个样本：2 个在 60/120 分钟发生，2 个在 30/600 分钟删失
        List<Stats.Survival> d = List.of(new Stats.Survival(60, true), new Stats.Survival(120, true),
                new Stats.Survival(30, false), new Stats.Survival(600, false));
        // t=30 删失后风险集 3 人：60 时 1−2/3 → 1/3 达成；120 时 S=2/3×1/2 → 2/3 达成
        assertThat(Stats.kmShareBy(d, 60)).isCloseTo(1 / 3.0, offset(1e-9));
        assertThat(Stats.kmShareBy(d, 120)).isCloseTo(2 / 3.0, offset(1e-9));
        assertThat(Stats.kmQuantile(d, 0.5)).isEqualTo(120);
    }
}
