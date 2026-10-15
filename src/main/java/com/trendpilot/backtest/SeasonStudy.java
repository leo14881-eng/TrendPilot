package com.trendpilot.backtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 山寨季轮动检验（日线，2018 年起）：先判断"山寨季"是否开启，开启时持有山寨，结束时退出拿现金。
 * 三种判断 × 两种持仓，和"一直拿 BTC""一直拿山寨"对比。日收盘判断，下一天收盘成交（晚一天，避免用到当天信息）。
 * 成本：每次调仓按换手比例扣手续费 0.1% + 1,000U 冲击（按成交额分档）。
 */
public final class SeasonStudy {

    static final double FEE = 0.001;
    static final int TOP = 100;
    static final int BASKET = 50;
    static final int PICKS = 10;
    static final int REBALANCE_DAYS = 7;

    private static final Pattern KEY = Pattern.compile("<Key>([^<]+\\.zip)</Key>");
    private static final Pattern NEXT = Pattern.compile("<NextMarker>([^<]+)</NextMarker>");

    final LocalDate startDate;
    final int days;
    final List<String> syms = new ArrayList<>();
    float[][] close;
    float[][] qv;
    int btc = -1;

    // 每天的指标
    double[] altIdx, altBtc, breadth, asi, btcClose;
    int[][] top;
    boolean[][] regime;
    static final String[] REGIMES = {"一直持有", "山寨/BTC 比值和山寨指数都在 50 日均线上方", "广度：≥60% 的山寨在自己 50 日均线上方开启，≤40% 结束",
            "山寨季指数：前 50 山寨里 ≥75% 的 90 天涨幅跑赢 BTC 开启，<50% 结束"};
    static final String[] HOLDS = {"前 50 山寨等权", "动量前 10（30 天涨幅最大，跌破 20 日均线提前卖）"};

    record Result(String name, double[] nav, double inMarket, int seasons) {
    }

    final List<Result> results = new ArrayList<>();
    final List<String> periods = new ArrayList<>();

    SeasonStudy(LocalDate startDate, int days) {
        this.startDate = startDate;
        this.days = days;
    }

    /** 下载（只下数据站上存在的包）并读入日线。 */
    static SeasonStudy load(Path dir, List<String> symbols, YearMonth from, YearMonth to, LocalDate lastDay) throws Exception {
        Archive a = new Archive(dir, "1d");
        LocalDate start = from.atDay(1);
        LocalDate end = lastDay != null ? lastDay : to.atEndOfMonth();
        int days = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
        long startMs = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Map<String, Series> loaded = new ConcurrentHashMap<>();
        AtomicInteger n = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    List<String> keys = list(a, dir, "data/spot/monthly/klines/" + s + "/1d/");
                    boolean alive = false;
                    for (String k : keys) {
                        YearMonth m = YearMonth.parse(k.substring(k.length() - 11, k.length() - 4));
                        if (m.isBefore(from) || m.isAfter(to)) continue;
                        a.fetch(k);
                        if (m.equals(to)) alive = true;
                    }
                    if (alive && lastDay != null && lastDay.isAfter(to.atEndOfMonth())) {
                        for (LocalDate d = lastDay.withDayOfMonth(1); !d.isAfter(lastDay); d = d.plusDays(1)) a.fetch(a.dailyPath(s, d));
                    }
                    Series x = keys.isEmpty() ? null : a.load(s, startMs, days, from, to, alive ? lastDay : null);
                    if (x != null) loaded.put(s, x);
                    if (n.incrementAndGet() % 200 == 0) System.out.println("日线进度 " + n.get() + "/" + symbols.size());
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    System.out.println("日线失败：" + e.getMessage());
                }
            }
        }
        SeasonStudy st = new SeasonStudy(start, days);
        st.init(loaded);
        System.out.println("日线读入 " + loaded.size() + " 个交易对");
        return st;
    }

    private static List<String> list(Archive a, Path dir, String prefix) throws IOException, InterruptedException {
        Path cache = dir.resolve(prefix + ".list");
        if (Files.exists(cache)) return Files.readAllLines(cache);
        List<String> out = new ArrayList<>();
        String marker = "";
        for (int page = 0; page < 5; page++) {
            String xml = a.get(Archive.LIST + "?prefix=" + prefix + (marker.isEmpty() ? "" : "&marker=" + marker));
            Matcher m = KEY.matcher(xml);
            String last = null;
            while (m.find()) {
                out.add(m.group(1));
                last = m.group(1);
            }
            if (!xml.contains("<IsTruncated>true</IsTruncated>") || last == null) break;
            Matcher nx = NEXT.matcher(xml);
            marker = nx.find() ? nx.group(1) : last;
        }
        Files.createDirectories(cache.getParent());
        Files.write(cache, out);
        return out;
    }

    void init(Map<String, Series> loaded) {
        List<String> names = new ArrayList<>(loaded.keySet());
        names.sort(null);
        close = new float[names.size()][];
        qv = new float[names.size()][];
        for (int i = 0; i < names.size(); i++) {
            Series s = loaded.get(names.get(i));
            syms.add(names.get(i));
            close[i] = s.close;
            qv[i] = s.quoteVolume;
            if (names.get(i).equals(MarketData.BTC)) btc = i;
        }
        if (btc < 0) throw new IllegalStateException("缺少 BTC 日线");
    }

    /** 杠杆代币（BTCUP、ETHDOWN、BULL、EOSBEAR 等）不算山寨。 */
    static boolean leveraged(String sym, Set<String> all) {
        String base = sym.substring(0, sym.length() - 4);
        for (String suf : new String[]{"UP", "DOWN", "BULL", "BEAR"}) {
            if (base.endsWith(suf)) {
                String under = base.substring(0, base.length() - suf.length());
                if (under.isEmpty() || all.contains(under + "USDT")) return true;
            }
        }
        return false;
    }

    boolean has(int i, int d) {
        return d >= 0 && d < days && !Float.isNaN(close[i][d]) && close[i][d] > 0;
    }

    double ret(int i, int d, int n) {
        return has(i, d) && has(i, d - n) ? close[i][d] / close[i][d - n] - 1 : Double.NaN;
    }

    double sma(int i, int d, int n) {
        double s = 0;
        int k = 0;
        for (int j = d - n + 1; j <= d; j++) {
            if (has(i, j)) {
                s += close[i][j];
                k++;
            }
        }
        return k < n * 0.8 ? Double.NaN : s / k;
    }

    static double sma(double[] a, int d, int n) {
        if (d - n + 1 < 0) return Double.NaN;
        double s = 0;
        for (int j = d - n + 1; j <= d; j++) {
            if (Double.isNaN(a[j])) return Double.NaN;
            s += a[j];
        }
        return s / n;
    }

    double vol30(int i, int d) {
        double s = 0;
        for (int j = d - 29; j <= d; j++) if (has(i, j)) s += qv[i][j];
        return s / 30;
    }

    /** 计算每天的山寨池、指数和三种山寨季判断。excluded = 稳定币、法币等。 */
    void prepare(Set<String> excluded) {
        Set<String> all = new java.util.HashSet<>(syms);
        boolean[] ok = new boolean[syms.size()];
        for (int i = 0; i < syms.size(); i++) {
            String s = syms.get(i);
            ok[i] = i != btc && !excluded.contains(s) && !leveraged(s, all);
        }
        top = new int[days][];
        altIdx = new double[days];
        altBtc = new double[days];
        breadth = new double[days];
        asi = new double[days];
        btcClose = new double[days];
        Arrays.fill(breadth, Double.NaN);
        Arrays.fill(asi, Double.NaN);
        double idx = 1;
        for (int d = 0; d < days; d++) {
            btcClose[d] = has(btc, d) ? close[btc][d] : (d > 0 ? btcClose[d - 1] : Double.NaN);
            List<double[]> cand = new ArrayList<>();
            for (int i = 0; i < syms.size(); i++) {
                if (ok[i] && has(i, d) && has(i, d - 59)) cand.add(new double[]{vol30(i, d), i});
            }
            cand.sort((x, y) -> Double.compare(y[0], x[0]));
            top[d] = cand.stream().limit(TOP).mapToInt(x -> (int) x[1]).toArray();
            // 山寨指数：昨天的池子今天的等权收益（不用今天才知道的池子）
            if (d > 0 && top[d - 1].length > 0) {
                double s = 0;
                int k = 0;
                for (int i : top[d - 1]) {
                    double r = ret(i, d, 1);
                    if (!Double.isNaN(r)) {
                        s += r;
                        k++;
                    }
                }
                if (k > 0) idx *= 1 + s / k;
            }
            altIdx[d] = idx;
            altBtc[d] = idx / btcClose[d];
            if (top[d].length >= 20) {
                int above = 0, k = 0;
                for (int i : top[d]) {
                    double m = sma(i, d, 50);
                    if (Double.isNaN(m)) continue;
                    k++;
                    if (close[i][d] > m) above++;
                }
                if (k >= 20) breadth[d] = above / (double) k;
                double b90 = ret(btc, d, 90);
                int win = 0, n = 0;
                for (int j = 0; j < Math.min(BASKET, top[d].length); j++) {
                    double r = ret(top[d][j], d, 90);
                    if (Double.isNaN(r) || Double.isNaN(b90)) continue;
                    n++;
                    if (r > b90) win++;
                }
                if (n >= 20) asi[d] = win / (double) n;
            }
        }
        regime = new boolean[REGIMES.length][days];
        boolean on2 = false, on3 = false;
        for (int d = 0; d < days; d++) {
            regime[0][d] = true;
            double ma1 = sma(altBtc, d, 50), ma2 = sma(altIdx, d, 50);
            regime[1][d] = !Double.isNaN(ma1) && !Double.isNaN(ma2) && altBtc[d] > ma1 && altIdx[d] > ma2;
            if (!Double.isNaN(breadth[d])) {
                if (breadth[d] >= 0.6) on2 = true;
                else if (breadth[d] <= 0.4) on2 = false;
            }
            regime[2][d] = on2;
            if (!Double.isNaN(asi[d])) {
                if (asi[d] >= 0.75) on3 = true;
                else if (asi[d] < 0.5) on3 = false;
            }
            regime[3][d] = on3;
        }
    }

    /** 跑全部方案。fromDay 起算净值（之前是指标预热）。 */
    void run(int fromDay) {
        results.add(btcHold(fromDay));
        for (int r = 0; r < REGIMES.length; r++) {
            for (int h = 0; h < HOLDS.length; h++) results.add(simulate(r, h, fromDay));
        }
    }

    private Result btcHold(int fromDay) {
        double[] nav = new double[days];
        Arrays.fill(nav, Double.NaN);
        nav[fromDay] = 1 - FEE - Costs.impact(1e9, 1000);
        for (int d = fromDay + 1; d < days; d++) nav[d] = nav[d - 1] * btcClose[d] / btcClose[d - 1];
        return new Result("一直持有 BTC（对照）", nav, 100, 0);
    }

    private Result simulate(int r, int h, int fromDay) {
        double[] nav = new double[days];
        Arrays.fill(nav, Double.NaN);
        Map<Integer, Double> w = new HashMap<>();
        double v = 1;
        nav[fromDay] = v;
        int inDays = 0, seasons = 0, sinceRebal = 1_000_000;
        boolean wasOn = false;
        int seasonStart = -1;
        double seasonNav = 1, seasonBtc = 1;
        for (int d = fromDay + 1; d < days; d++) {
            // 1) 今天的收益（持仓是昨天收盘定的）
            double port = 0;
            Map<Integer, Double> next = new HashMap<>();
            for (var e : w.entrySet()) {
                int i = e.getKey();
                double rr = ret(i, d, 1);
                if (Double.isNaN(rr)) rr = 0; // 停牌/下架：按最后收盘价转成现金
                port += e.getValue() * rr;
                if (has(i, d)) next.put(i, e.getValue() * (1 + rr));
            }
            double cashW = 1 - w.values().stream().mapToDouble(Double::doubleValue).sum();
            double total = cashW + w.values().stream().mapToDouble(Double::doubleValue).sum() + port;
            v *= 1 + port;
            w = new HashMap<>();
            for (var e : next.entrySet()) w.put(e.getKey(), e.getValue() / total);
            if (!w.isEmpty()) inDays++;
            // 2) 今天收盘决定的目标仓位：信号用昨天收盘的数据（晚一天）
            boolean on = regime[r][d - 1];
            Map<Integer, Double> target = w;
            sinceRebal++;
            if (!on) {
                target = Map.of();
            } else if (!wasOn || sinceRebal >= REBALANCE_DAYS) {
                target = pick(h, d - 1);
                sinceRebal = 0;
            } else if (h == 1) {
                // 动量持仓：跌破 20 日均线的提前卖
                target = new HashMap<>(w);
                for (int i : w.keySet()) {
                    double m = sma(i, d - 1, 20);
                    if (has(i, d - 1) && !Double.isNaN(m) && close[i][d - 1] < m) target.remove(i);
                }
            }
            if (on && !wasOn) {
                seasons++;
                seasonStart = d;
                seasonNav = v;
                seasonBtc = btcClose[d];
            }
            if (!on && wasOn && r > 0 && h == 0) addPeriod(r, seasonStart, d, v / seasonNav - 1, btcClose[d] / seasonBtc - 1);
            wasOn = on;
            if (target != w) {
                double cost = 0;
                Set<Integer> keys = new java.util.HashSet<>(w.keySet());
                keys.addAll(target.keySet());
                for (int i : keys) {
                    double dw = Math.abs(target.getOrDefault(i, 0.0) - w.getOrDefault(i, 0.0));
                    double vol = has(i, d) ? qv[i][d] : 0;
                    cost += dw * (FEE + Costs.impact(vol, 1000));
                }
                v *= 1 - cost;
                w = new HashMap<>(target);
            }
            nav[d] = v;
        }
        if (wasOn && r > 0 && h == 0) addPeriod(r, seasonStart, days - 1, v / seasonNav - 1, btcClose[days - 1] / seasonBtc - 1);
        return new Result(REGIMES[r] + " → " + HOLDS[h], nav, inDays * 100.0 / Math.max(1, days - fromDay - 1), seasons);
    }

    private void addPeriod(int r, int from, int to, double ret, double btcRet) {
        periods.add(String.format(Locale.ROOT, "%d,%s,%s,%d,%.2f,%.2f", r, date(from), date(to), to - from, ret * 100, btcRet * 100));
    }

    /** 在第 d 天收盘时挑持仓（只用 d 及以前的数据）。 */
    Map<Integer, Double> pick(int h, int d) {
        Map<Integer, Double> t = new HashMap<>();
        int[] u = top[d];
        if (h == 0) {
            int n = Math.min(BASKET, u.length);
            for (int j = 0; j < n; j++) t.put(u[j], 1.0 / n);
        } else {
            List<double[]> m = new ArrayList<>();
            for (int i : u) {
                double r = ret(i, d, 30);
                double ma = sma(i, d, 20);
                if (!Double.isNaN(r) && !Double.isNaN(ma) && close[i][d] > ma) m.add(new double[]{r, i});
            }
            m.sort((x, y) -> Double.compare(y[0], x[0]));
            int n = Math.min(PICKS, m.size());
            for (int j = 0; j < n; j++) t.put((int) m.get(j)[1], 1.0 / PICKS);
        }
        return t;
    }

    String date(int d) {
        return startDate.plusDays(d).toString();
    }

    int dayOf(LocalDate x) {
        return (int) (x.toEpochDay() - startDate.toEpochDay());
    }

    static double[] stats(double[] nav, int from, int to) {
        double base = Double.NaN, peak = Double.NaN, dd = 0, last = Double.NaN;
        for (int d = Math.max(0, from); d <= to && d < nav.length; d++) {
            if (Double.isNaN(nav[d])) continue;
            if (Double.isNaN(base)) {
                base = nav[d];
                peak = nav[d];
            }
            peak = Math.max(peak, nav[d]);
            dd = Math.max(dd, 1 - nav[d] / peak);
            last = nav[d];
        }
        return new double[]{(last / base - 1) * 100, dd * 100};
    }

    public String markdown(int fromDay) {
        StringBuilder b = new StringBuilder("## 山寨季轮动（日线，" + date(fromDay) + " ～ " + date(days - 1) + "）\n\n");
        b.append("山寨池 = 每天按 30 天平均成交额取前 100 个 USDT 现货（不含 BTC、稳定币、杠杆代币，含后来下架的币，上线满 60 天）。")
                .append("每天收盘判断，下一天收盘成交；每 7 天调仓一次；调仓按换手扣手续费 0.1% + 冲击。不在山寨季时拿现金（USDT，不计利息）。\n\n");
        b.append("**可信度提醒：这段时间只有 2021 和 2024 年底两段比较明显的山寨行情，独立样本很少，下面的结果只能当方向参考，不能当已验证的规则。**\n\n");
        int[] cuts = {fromDay, dayOf(LocalDate.of(2020, 1, 1)), dayOf(LocalDate.of(2022, 1, 1)), dayOf(LocalDate.of(2024, 1, 1)), days - 1};
        b.append("| 方案 | 全程收益% | 最大回撤% | 持仓时间% | 山寨季次数 | ")
                .append(date(cuts[0]).substring(0, 4)).append("–2019 收益/回撤 | 2020–2021 收益/回撤 | 2022–2023 收益/回撤 | 2024–").append(date(days - 1).substring(0, 4)).append(" 收益/回撤 |\n|---|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            double[] all = stats(r.nav(), fromDay, days - 1);
            b.append(String.format(Locale.ROOT, "| %s | %.0f | %.1f | %.0f | %d |", r.name(), all[0], all[1], r.inMarket(), r.seasons()));
            for (int k = 0; k < 4; k++) {
                double[] s = stats(r.nav(), cuts[k], cuts[k + 1]);
                b.append(String.format(Locale.ROOT, " %.0f / %.0f |", s[0], s[1]));
            }
            b.append('\n');
        }
        b.append("\n### 每段山寨季（持有前 50 等权时）\n\n| 判断方法 | 开始 | 结束 | 天数 | 这段山寨收益% | 同期 BTC% |\n|---|---|---|---|---|---|\n");
        for (String p : periods) {
            String[] c = p.split(",");
            int r = Integer.parseInt(c[0]);
            if (Integer.parseInt(c[3]) < 14) continue;
            b.append("| ").append(r).append(" | ").append(c[1]).append(" | ").append(c[2]).append(" | ").append(c[3]).append(" | ").append(c[4]).append(" | ").append(c[5]).append(" |\n");
        }
        b.append("\n（判断方法编号：1 = ").append(REGIMES[1]).append("；2 = ").append(REGIMES[2]).append("；3 = ").append(REGIMES[3]).append("。只列 14 天以上的段，全部在 season-periods.csv。）\n\n");
        return b.toString();
    }

    public void writeCsv(Path dir) throws IOException {
        StringBuilder h = new StringBuilder("date,btc_close,alt_index,alt_btc,breadth,altseason_index,top100_count,r1,r2,r3");
        for (Result r : results) h.append(",nav_").append(results.indexOf(r));
        List<String> lines = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            StringBuilder l = new StringBuilder(date(d)).append(String.format(Locale.ROOT, ",%.2f,%.6f,%.10f,%.4f,%.4f,%d,%d,%d,%d", btcClose[d], altIdx[d], altBtc[d],
                    breadth[d], asi[d], top[d].length, regime[1][d] ? 1 : 0, regime[2][d] ? 1 : 0, regime[3][d] ? 1 : 0));
            for (Result r : results) l.append(String.format(Locale.ROOT, ",%.6f", r.nav()[d]));
            lines.add(l.toString());
        }
        StringBuilder names = new StringBuilder("# 净值列编号: ");
        for (int k = 0; k < results.size(); k++) names.append(k).append('=').append(results.get(k).name()).append("; ");
        Files.writeString(dir.resolve("season-daily.csv"), names + "\n" + h + "\n" + String.join("\n", lines) + "\n");
        Files.writeString(dir.resolve("season-periods.csv"), "regime,start,end,days,alt_basket_pct,btc_pct\n" + String.join("\n", periods) + "\n");
        // 每天的山寨池成员（核对用）
        List<String> pool = new ArrayList<>();
        for (int d = 0; d < days; d += 7) {
            StringBuilder l = new StringBuilder(date(d));
            for (int i : top[d]) l.append(',').append(syms.get(i));
            pool.add(l.toString());
        }
        Files.writeString(dir.resolve("season-pool-weekly.csv"), "date,symbols_by_30d_volume\n" + String.join("\n", pool) + "\n");
    }

    LinkedHashMap<String, double[]> navs() {
        LinkedHashMap<String, double[]> m = new LinkedHashMap<>();
        for (Result r : results) m.put(r.name(), r.nav());
        return m;
    }
}
