package com.trendpilot.btctrend;

import java.util.ArrayList;
import java.util.List;

/**
 * BTC 均线趋势（4 条均线 × 4 份）的规则，与用户交接的参考实现 trend_ref.py 逐行等价（docs/btc-trend/trend_ref.py 是唯一标准）：
 * <ul>
 * <li>本金分 4 份，分别绑定日线收盘价简单均线 SMA75 / SMA100 / SMA125 / SMA150（均线含信号日 D 当天）。</li>
 * <li>用已收盘的日线 D 判断、D+1 开盘执行：该份是 USDT 且 close_D &gt; SMA_D × 1.02 → 全部买成 BTC；
 *     该份是 BTC 且 close_D &lt; SMA_D × 0.98 → 全部卖成 USDT；其他不动。历史不足 n 根时该份不动。</li>
 * <li>手续费 0.1%/笔；4 份独立记账，盈亏各自滚动。</li>
 * </ul>
 * 这是单独的程序和资金，会在跌破卖出线时亏本卖出（用户 2026-10-23 确认），不受 BTC 每日短线"不卖亏"规则约束。
 */
public final class MaTrendRules {

    public static final int[] MAS = {75, 100, 125, 150};
    public static final double BAND = 0.02;
    public static final double FEE = 0.001;
    public static final double CAPITAL = 20_000.0;

    public enum Action { BUY, SELL, HOLD }

    private MaTrendRules() {
    }

    /** closes[i-n+1 .. i] 的简单平均；历史不足返回 null。 */
    public static Double sma(double[] closes, int i, int n) {
        if (i - n + 1 < 0 || i >= closes.length) return null;
        double s = 0;
        for (int k = i - n + 1; k <= i; k++) s += closes[k];
        return s / n;
    }

    /** 已收盘日线 i 对一份的信号。holding = 这份是否持有 BTC。 */
    public static Action signal(double[] closes, int i, int n, boolean holding) {
        Double m = sma(closes, i, n);
        if (m == null) return Action.HOLD;
        double c = closes[i];
        if (!holding && c > m * (1 + BAND)) return Action.BUY;
        if (holding && c < m * (1 - BAND)) return Action.SELL;
        return Action.HOLD;
    }

    public record Bar(String date, double open, double close) {
    }

    public record Trade(String execDate, int sma, Action side, double price, double usdt, double btc) {
    }

    public record Result(List<Trade> trades, double finalValue, double maxDrawdown, boolean[] holding) {
    }

    /** 与 trend_ref.backtest 相同：从 startDate 起，每天用前一根已收盘日线的信号按当天开盘价执行。 */
    public static Result backtest(List<Bar> bars, String startDate, double capital) {
        int i0 = 0;
        while (i0 < bars.size() && bars.get(i0).date().compareTo(startDate) < 0) i0++;
        double[] closes = bars.stream().mapToDouble(Bar::close).toArray();
        int k = MAS.length;
        double per = capital / k;
        double[] usd = new double[k];
        double[] btc = new double[k];
        boolean[] holding = new boolean[k];
        java.util.Arrays.fill(usd, per);
        List<Trade> trades = new ArrayList<>();
        double peak = 0, mdd = 0;
        for (int i = i0; i < bars.size(); i++) {
            double px = bars.get(i).open();
            for (int s = 0; s < k; s++) {
                Action a = i - 1 >= 0 ? signal(closes, i - 1, MAS[s], holding[s]) : Action.HOLD;
                if (a == Action.BUY) {
                    btc[s] = usd[s] / px * (1 - FEE);
                    trades.add(new Trade(bars.get(i).date(), MAS[s], a, px, usd[s], btc[s]));
                    usd[s] = 0;
                    holding[s] = true;
                } else if (a == Action.SELL) {
                    usd[s] = btc[s] * px * (1 - FEE);
                    trades.add(new Trade(bars.get(i).date(), MAS[s], a, px, usd[s], btc[s]));
                    btc[s] = 0;
                    holding[s] = false;
                }
            }
            double v = sum(btc) * bars.get(i).close() + sum(usd);
            peak = Math.max(peak, v);
            mdd = Math.min(mdd, v / peak - 1);
        }
        double fin = sum(btc) * bars.getLast().close() + sum(usd);
        return new Result(trades, fin, mdd, holding);
    }

    private static double sum(double[] a) {
        double s = 0;
        for (double x : a) s += x;
        return s;
    }
}
