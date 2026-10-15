package com.trendpilot.backtest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 大涨前埋伏检验：找"还没大涨、但之后容易大涨"的迹象。
 * 每个整点收盘检查迹象；同一个币同一迹象 24 小时只算第一次；下一根 15m 开盘买入。
 * 看三件事：之后 24 小时内最高涨到 +10% / +20% 的比例（和随便买比）、各种卖法扣成本后的每笔收益、
 * 以及所有"24 小时内涨过 20%"的大涨里，事前 24 小时内出现过这个迹象的比例（抓到率）。
 */
public final class PrePumpStudy {

    static final int DAY = MarketData.DAY;
    static final int H = 4;

    interface Cond {
        boolean hit(Series s, int q, MarketData.Context ctx);
    }

    record Sign(String name, Cond cond) {
    }

    static List<Sign> signs() {
        List<Sign> t = new ArrayList<>();
        t.add(new Sign("随便买（每个币每天一次，对照）", (s, q, c) -> q % DAY == DAY - 1));
        t.add(new Sign("4 小时放量 3 倍、价格没动（±2%）", (s, q, c) -> volRatio(s, q, 4 * H) >= 3 && Math.abs(ret(s, q, 4 * H)) < 2));
        t.add(new Sign("24 小时放量 2 倍、价格没动（±3%）", (s, q, c) -> volRatio(s, q, DAY) >= 2 && Math.abs(ret(s, q, DAY)) < 3));
        t.add(new Sign("连续 3 天成交额递增、3 天价格横盘（±5%）", (s, q, c) -> rising3(s, q) && Math.abs(ret(s, q, 3 * DAY)) < 5));
        t.add(new Sign("波动收窄：24h 振幅不到前 6 天平均的一半", (s, q, c) -> squeeze(s, q) < 0.5));
        t.add(new Sign("收窄后放量突破 24h 高点", (s, q, c) -> squeeze(s, q - H) < 0.6 && breakout(s, q) && volRatio(s, q, H) >= 2));
        t.add(new Sign("慢慢走强：7 天涨 10~30%、贴近高点、这小时没急涨", (s, q, c) -> {
            double r7 = ret(s, q, 7 * DAY);
            return r7 >= 10 && r7 <= 30 && fromHigh(s, q, 7 * DAY) > -3 && Math.abs(ret(s, q, H)) < 2;
        }));
        t.add(new Sign("跌了很多后底部放量：30 天跌 ≥30%，24h 量 3 倍，价格稳", (s, q, c) -> {
            double r24 = ret(s, q, DAY);
            return ret(s, q, 30 * DAY) <= -30 && volRatio(s, q, DAY) >= 3 && r24 > -2 && r24 < 5;
        }));
        t.add(new Sign("大盘跌它不跌：山寨 24h 中位数 ≤-3%，它 ≥+2%", (s, q, c) -> c != null && c.alt24h() <= -3 && ret(s, q, DAY) >= 2));
        t.add(new Sign("小涨刚开始：1 小时 +2~4%、放量 2 倍、24h 涨不到 5%", (s, q, c) -> {
            double r1 = ret(s, q, H);
            return r1 >= 2 && r1 < 4 && volRatio(s, q, H) >= 2 && ret(s, q, DAY) < 5;
        }));
        return t;
    }

    static List<PumpStudy.Exit> exits() {
        List<PumpStudy.Exit> e = new ArrayList<>();
        e.add(new PumpStudy.Exit("持有 24 小时", DAY, 0, 0, 0));
        e.add(new PumpStudy.Exit("持有 72 小时", 3 * DAY, 0, 0, 0));
        e.add(new PumpStudy.Exit("从最高回落 5% 卖（最长 72h）", 3 * DAY, 5, 0, 0));
        e.add(new PumpStudy.Exit("赚 10% 卖 / 亏 5% 卖（最长 72h）", 3 * DAY, 0, 10, 5));
        e.add(new PumpStudy.Exit("赚 20% 卖 / 亏 7% 卖（最长 72h）", 3 * DAY, 0, 20, 7));
        return e;
    }

    private final MarketData data;
    private final List<Sign> signs = signs();
    private final List<PumpStudy.Exit> exits = exits();
    /** [迹象] → 每笔 {天, 年份, 24h 最高涨%, 各卖法净收益%..., 各卖法不算成本%...}。 */
    private final List<List<double[]>> rows = new ArrayList<>();
    /** 大涨事件数，和事前 24h 出现过各迹象的数量。 */
    private int pumps;
    private final int[] caught;
    /** 事前 24h 出现过任意一种迹象（9 种合并去重）的大涨数。 */
    private int caughtAny;
    private final List<String> tradeLog = new ArrayList<>();
    private final List<String> eventLog = new ArrayList<>();

    public PrePumpStudy(MarketData data) {
        this.data = data;
        for (int i = 0; i < signs.size(); i++) rows.add(new ArrayList<>());
        caught = new int[signs.size()];
    }

    public void run(int fromQ, int toQ) {
        Map<String, int[]> last = new HashMap<>();
        int end = toQ - 3 * DAY - 2;
        for (int q = Math.max(fromQ, 31 * DAY); q < end; q++) {
            if (q % H != H - 1) continue;
            MarketData.Context ctx = data.context(q);
            int day = q / DAY;
            int year = Instant.ofEpochMilli(data.time(q)).atZone(ZoneOffset.UTC).getYear();
            for (String sym : data.universe(q)) {
                if (MarketData.ETH.equals(sym) || MarketData.BTC.equals(sym)) continue;
                Series s = data.get(sym);
                if (s == null || !s.has(q) || !s.has(q + 1) || !s.has(q - 7 * DAY)) continue;
                int[] lastHit = last.computeIfAbsent(sym, k -> filled(signs.size()));
                double open = s.open[q + 1];
                double maxUp = maxUp(s, q + 1, DAY, open);
                // 抓到率：每个币每天看一次，之后 24h 涨过 20% 算一次大涨（不含对照组）
                if (q % DAY == DAY - 1 && maxUp >= 20) {
                    pumps++;
                    boolean any = false;
                    StringBuilder ev = new StringBuilder(sym).append(',').append(Instant.ofEpochMilli(data.time(q + 1))).append(',')
                            .append(open).append(',').append(String.format(Locale.ROOT, "%.3f", maxUp));
                    for (int k = 1; k < signs.size(); k++) {
                        boolean seen = q - lastHit[k] <= DAY;
                        if (seen) caught[k]++;
                        any |= seen;
                        ev.append(',').append(seen ? 1 : 0);
                    }
                    if (any) caughtAny++;
                    eventLog.add(ev.toString());
                }
                var t = data.ticker(sym, q);
                if (t == null) continue;
                double imp = Costs.impact(t.quoteVolume(), 1000);
                for (int k = 0; k < signs.size(); k++) {
                    if (k > 0 && q - lastHit[k] < DAY) continue;
                    boolean hit;
                    try {
                        hit = signs.get(k).cond().hit(s, q, ctx);
                    } catch (RuntimeException e) {
                        hit = false;
                    }
                    if (!hit) continue;
                    lastHit[k] = q;
                    double[] r = new double[3 + 2 * exits.size()];
                    r[0] = day;
                    r[1] = year;
                    r[2] = maxUp;
                    double entry = open * (1 + imp) * (1 + PumpStudy.FEE);
                    boolean ok = true;
                    for (int j = 0; j < exits.size(); j++) {
                        double px = PumpStudy.exit(s, q + 1, exits.get(j), open, toQ);
                        if (Double.isNaN(px)) ok = false;
                        r[3 + j] = (px * (1 - imp) * (1 - PumpStudy.FEE) / entry - 1) * 100;
                        r[3 + exits.size() + j] = (px / open - 1) * 100;
                    }
                    if (ok && !Double.isNaN(maxUp)) {
                        rows.get(k).add(r);
                        StringBuilder line = new StringBuilder().append(k).append(',').append(sym).append(',')
                                .append(Instant.ofEpochMilli(data.time(q + 1))).append(',').append(open).append(',')
                                .append(String.format(Locale.ROOT, "%.3f,%.3f", imp * 100, maxUp));
                        for (int j = 0; j < exits.size(); j++) line.append(String.format(Locale.ROOT, ",%.4f,%.4f", r[3 + j], r[3 + exits.size() + j]));
                        tradeLog.add(line.toString());
                    }
                }
            }
        }
    }

    private static int[] filled(int n) {
        int[] a = new int[n];
        Arrays.fill(a, Integer.MIN_VALUE / 2);
        return a;
    }

    static double maxUp(Series s, int b, int n, double open) {
        double hi = Double.NaN;
        for (int i = b; i < Math.min(b + n, s.size()); i++) if (s.has(i)) hi = Double.isNaN(hi) ? s.high[i] : Math.max(hi, s.high[i]);
        return Double.isNaN(hi) || open <= 0 ? Double.NaN : (hi / open - 1) * 100;
    }

    static double ret(Series s, int q, int n) {
        return PumpStudy.ret(s, q, n);
    }

    /** 最近 n 根成交额 ÷ 之前 7 天同样长度的平均。 */
    static double volRatio(Series s, int q, int n) {
        double recent = s.volume(q, n);
        double prior = s.volume(q - n, 7 * DAY) / (7 * DAY) * n;
        return prior <= 0 ? Double.NaN : recent / prior;
    }

    static boolean rising3(Series s, int q) {
        double d0 = s.volume(q, DAY), d1 = s.volume(q - DAY, DAY), d2 = s.volume(q - 2 * DAY, DAY);
        return d2 > 0 && d1 >= d2 * 1.3 && d0 >= d1 * 1.3;
    }

    /** 最近 24h 振幅 ÷ 之前 6 天每天振幅的平均。 */
    static double squeeze(Series s, int q) {
        double now = range(s, q, DAY);
        double sum = 0;
        int k = 0;
        for (int d = 1; d <= 6; d++) {
            double r = range(s, q - d * DAY, DAY);
            if (!Double.isNaN(r)) {
                sum += r;
                k++;
            }
        }
        return k < 5 || sum <= 0 || Double.isNaN(now) ? Double.NaN : now / (sum / k);
    }

    static double range(Series s, int q, int n) {
        double hi = Double.NaN, lo = Double.NaN;
        for (int i = Math.max(0, q - n + 1); i <= q; i++) {
            if (!s.has(i)) continue;
            hi = Double.isNaN(hi) ? s.high[i] : Math.max(hi, s.high[i]);
            lo = Double.isNaN(lo) ? s.low[i] : Math.min(lo, s.low[i]);
        }
        return Double.isNaN(hi) || lo <= 0 ? Double.NaN : hi / lo - 1;
    }

    /** 这一小时收盘高于之前 24h 的最高价。 */
    static boolean breakout(Series s, int q) {
        double hi = Double.NaN;
        for (int i = q - H - DAY + 1; i <= q - H; i++) if (i >= 0 && s.has(i)) hi = Double.isNaN(hi) ? s.high[i] : Math.max(hi, s.high[i]);
        return !Double.isNaN(hi) && s.close[q] > hi;
    }

    static double fromHigh(Series s, int q, int n) {
        return PumpStudy.fromHigh(s, q, n);
    }

    /** 交易明细和大涨事件明细。 */
    public void writeCsv(java.nio.file.Path dir) throws java.io.IOException {
        StringBuilder h = new StringBuilder("# 迹象编号: ");
        for (int k = 0; k < signs.size(); k++) h.append(k).append('=').append(signs.get(k).name()).append("; ");
        h.append("\n# 卖法编号: ");
        for (int j = 0; j < exits.size(); j++) h.append(j).append('=').append(exits.get(j).name()).append("; ");
        h.append("\nsign,symbol,entry_time_utc,entry_open,impact_pct,max_up_24h_pct");
        for (int j = 0; j < exits.size(); j++) h.append(",net_").append(j).append(",gross_").append(j);
        Csv.writeGz(dir.resolve("prepump-trades.csv.gz"), h.toString(), tradeLog);
        StringBuilder e = new StringBuilder("# 每个币每天 UTC 23:45 收盘看一次，之后 24h 最高涨 ≥20% 记一次大涨；sign_k=1 表示事前 24 小时内出现过第 k 种迹象\nsymbol,entry_time_utc,entry_open,max_up_24h_pct");
        for (int k = 1; k < signs.size(); k++) e.append(",sign_").append(k);
        Csv.writeGz(dir.resolve("prepump-events.csv.gz"), e.toString(), eventLog);
    }

    int count(int sign) {
        return rows.get(sign).size();
    }

    public String markdown() {
        StringBuilder b = new StringBuilder("## 大涨前埋伏检验（整点检查迹象；下一根 15 分钟开盘买入；同一币同一迹象 24 小时只算一次；不含 BTC/ETH）\n\n");
        b.append("「之后 24h 涨到 +10% / +20%」= 买入后 24 小时内最高价达到的比例，和第一行「随便买」比，高出越多说明迹象越有用。")
                .append("「抓到率」= 所有 24 小时内涨过 20% 的大涨里，事前 24 小时内出现过这个迹象的比例。净收益扣来回手续费和 1,000U 冲击。\n\n");
        b.append(String.format(Locale.ROOT, "大涨事件（某币某天之后 24h 内最高涨 ≥20%%）共 %d 次；事前 24 小时内出现过 9 种迹象中任意一种的（合并去重）%d 次，合并抓到率 %.0f%%。%n%n",
                pumps, caughtAny, pumps == 0 ? 0 : caughtAny * 100.0 / pumps));
        b.append("| 迹象 | 笔数 | 每天平均笔数 | 之后 24h 涨到 +10% | 涨到 +20% | 抓到率 |");
        for (PumpStudy.Exit e : exits) b.append(' ').append(e.name()).append(" 净% |");
        b.append(" 最好卖法不算成本% |\n|---|---|---|---|---|---|");
        for (int j = 0; j < exits.size(); j++) b.append("---|");
        b.append("---|\n");
        for (int k = 0; k < signs.size(); k++) {
            List<double[]> rs = rows.get(k);
            if (rs.isEmpty()) continue;
            long days = rs.stream().mapToInt(r -> (int) r[0]).distinct().count();
            double p10 = rs.stream().filter(r -> r[2] >= 10).count() * 100.0 / rs.size();
            double p20 = rs.stream().filter(r -> r[2] >= 20).count() * 100.0 / rs.size();
            b.append(String.format(Locale.ROOT, "| %s | %d | %.1f | %.1f%% | %.1f%% | %s |", signs.get(k).name(), rs.size(),
                    rs.size() / (double) Math.max(1, days), p10, p20, k == 0 || pumps == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", caught[k] * 100.0 / pumps)));
            int best = 0;
            double bestMean = -1e9;
            for (int j = 0; j < exits.size(); j++) {
                int col = 3 + j;
                double m = rs.stream().mapToDouble(r -> r[col]).average().orElse(0);
                if (m > bestMean) {
                    bestMean = m;
                    best = j;
                }
                b.append(String.format(Locale.ROOT, " %.2f |", m));
            }
            int gcol = 3 + exits.size() + best;
            b.append(String.format(Locale.ROOT, " %.2f |%n", rs.stream().mapToDouble(r -> r[gcol]).average().orElse(0)));
        }
        b.append("\n### 每种迹象最好卖法的可信区间和按年份（每笔净收益%）\n\n| 迹象 | 卖法 | 每笔平均 | 95% 区间（围绕每笔平均，按天聚类） | 赚钱比例 | 按年份 |\n|---|---|---|---|---|---|\n");
        for (int k = 0; k < signs.size(); k++) {
            List<double[]> rs = rows.get(k);
            if (rs.isEmpty()) continue;
            int best = 0;
            double bestMean = -1e9;
            for (int j = 0; j < exits.size(); j++) {
                int col = 3 + j;
                double m = rs.stream().mapToDouble(r -> r[col]).average().orElse(0);
                if (m > bestMean) {
                    bestMean = m;
                    best = j;
                }
            }
            int col = 3 + best;
            List<double[]> pairs = rs.stream().map(r -> new double[]{r[col], r[0]}).toList();
            double[] ci = PumpStudy.clusterCi(pairs);
            double win = rs.stream().filter(r -> r[col] > 0).count() * 100.0 / rs.size();
            StringBuilder yr = new StringBuilder();
            rs.stream().mapToInt(r -> (int) r[1]).distinct().sorted().forEach(y -> {
                double m = rs.stream().filter(r -> r[1] == y).mapToDouble(r -> r[col]).average().orElse(0);
                yr.append(y).append(": ").append(String.format(Locale.ROOT, "%.2f", m)).append("；");
            });
            b.append(String.format(Locale.ROOT, "| %s | %s | %.2f | %.2f ~ %.2f | %.0f%% | %s |%n", signs.get(k).name(), exits.get(best).name(),
                    bestMean, ci[1], ci[2], win, yr));
        }
        return b.append('\n').toString();
    }
}
