package com.trendpilot.altseason;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.trendpilot.job.AppState;
import com.trendpilot.market.Kline;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.notify.Notifier;
import com.trendpilot.research.ResearchService;
import com.trendpilot.research.StablecoinClient;
import com.trendpilot.universe.UniverseFilter;

/**
 * 山寨季仪表盘（用户 2026-11-08 授权自动优化；只显示信息，不触发任何买卖、不读写任何账本）。
 * 每天 UTC 00:07（越南 07:07）山寨季指标算完后生成一节，写 alt_dashboard_daily 和 data/reports/alt-dashboard-日期.md，
 * 发一条【山寨季仪表盘】Telegram：
 * <ol>
 * <li>现有山寨季指标（线上状态行原样）；</li>
 * <li>5 个前兆指标的状态和离阈值多远（定义见 {@link AltEarlySignals}，历史误报 7～8 成）；</li>
 * <li>S2 开关；</li>
 * <li>轮动 21 日前 5（Binance、Bybit 各一份）；</li>
 * <li>最强 3 个板块（CoinGecko 板块，等权 7 日 / 21 日收益）和龙头币。</li>
 * </ol>
 * 任何一项取不到都显示"-"，整节照常生成；生成失败只记日志，不影响山寨季提醒和其他任务。
 * 请求：Binance 日线和山寨季指标共用同一批（不另发）；Bybit 成交额前 60 + BTC 各 1 次日线；DefiLlama 稳定币每天最多 2 次（存 SQLite）。
 */
@Component
public class AltDashboard {

    private static final Logger log = LoggerFactory.getLogger(AltDashboard.class);
    static final String STABLE_ATTEMPTS = "altdash:stable_attempts";
    static final int BYBIT_CANDIDATES = 60;
    static final int BYBIT_DAYS = 150;
    static final int MIN_SECTOR = 3;
    static final String NOTE = "历史上误报 7–8 成，仅供观察（不触发任何买卖）";
    static final Map<String, String> SECTOR_NAMES = Map.ofEntries(
            Map.entry("layer-1", "公链 L1"), Map.entry("layer-2", "L2"), Map.entry("meme-token", "Meme"),
            Map.entry("artificial-intelligence", "AI"), Map.entry("decentralized-finance-defi", "DeFi"), Map.entry("gaming", "游戏"),
            Map.entry("real-world-assets-rwa", "RWA"), Map.entry("depin", "DePIN"), Map.entry("privacy-coins", "隐私币"),
            Map.entry("exchange-based-tokens", "平台币"));

    private final Markets markets;
    private final UniverseFilter filter;
    private final ResearchService research;
    private final StablecoinClient stablecoins;
    private final AppState state;
    private final JdbcClient jdbc;
    private final Notifier notifier;
    private final Path dir;

    public AltDashboard(Markets markets, UniverseFilter filter, ResearchService research, StablecoinClient stablecoins, AppState state,
                        JdbcClient jdbc, Notifier notifier, @Value("${trendpilot.data-dir:./data}") String dataDir) {
        this.markets = markets;
        this.filter = filter;
        this.research = research;
        this.stablecoins = stablecoins;
        this.state = state;
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.dir = Path.of(dataDir, "reports");
    }

    /**
     * 生成某天的一节并发送（同一天只生成一次）。
     *
     * @param day         刚收盘的 UTC 日
     * @param binanceBars 山寨季指标已取到的 Binance 已收盘日线（池内山寨 + BTC），可为空
     * @param altLine     线上山寨季状态行（没有时为空）
     */
    public Optional<String> run(LocalDate day, Map<String, List<Kline>> binanceBars, Optional<String> altLine, Instant now) {
        if (latestDay().filter(d -> !d.isBefore(day)).isPresent()) return Optional.empty();
        String body = build(day, binanceBars, altLine, now);
        save(day, body, now);
        notifier.send("altdash:" + day, body);
        return Optional.of(body);
    }

    /** 最近一份已生成的仪表盘（GET /api/alt-dashboard）。 */
    public Optional<String> latest() {
        return jdbc.sql("SELECT body FROM alt_dashboard_daily ORDER BY day DESC LIMIT 1").query(String.class).optional();
    }

    Optional<LocalDate> latestDay() {
        return jdbc.sql("SELECT MAX(day) FROM alt_dashboard_daily").query(String.class).optional().map(LocalDate::parse);
    }

    // ------------------------------------------------------------------ build

    /** 整节 Markdown；每一项单独兜底，任何一项出错只把那一项显示成"-"。 */
    String build(LocalDate day, Map<String, List<Kline>> binanceBars, Optional<String> altLine, Instant now) {
        List<String> out = new ArrayList<>();
        out.add("【山寨季仪表盘】UTC " + day + " 收盘（只读信息，不交易）");
        out.add("");
        out.add("1. 现有山寨季指标：" + altLine.orElse("-"));
        AltEarlySignals.Panel bp = null;
        AltEarlySignals.Status st = null;
        try {
            bp = panel(binanceBars, day, AltSeasonJob.DAYS);
            Map<Long, Double> stable = stableSeries(day, now);
            if (bp != null && bp.btc >= 0) {
                AltEarlySignals.Signals s = AltEarlySignals.signals(bp, stable);
                st = AltEarlySignals.status(bp, s, bp.days() - 1);
            }
        } catch (RuntimeException e) {
            log.info("山寨季仪表盘：前兆指标这次算不出来（显示\"-\"）：{}", e.toString());
        }
        final AltEarlySignals.Panel binance = bp;
        out.addAll(indicators(st));
        out.add(s2Line(st));
        out.add("4. 轮动 21 日前 5（成交额前 40，按 21 日收益 ÷ 21 日波动排名）：");
        out.add("   - Binance：" + safe(() -> rotationLine(bp(binance))));
        out.add("   - Bybit：" + safe(() -> rotationLine(bybitPanel(day))));
        out.add("5. 最强 3 个板块（等权 7 日 / 21 日收益）：");
        for (String l : safeList(() -> sectorLines(bp(binance)))) out.add("   - " + l);
        return String.join("\n", out) + "\n";
    }

    private static AltEarlySignals.Panel bp(AltEarlySignals.Panel p) {
        if (p == null || p.days() == 0) throw new IllegalStateException("没有 Binance 日线");
        return p;
    }

    static String safe(java.util.function.Supplier<String> f) {
        try {
            String s = f.get();
            return s == null || s.isBlank() ? "-" : s;
        } catch (RuntimeException e) {
            log.info("山寨季仪表盘：一项取不到（显示\"-\"）：{}", e.toString());
            return "-";
        }
    }

    static List<String> safeList(java.util.function.Supplier<List<String>> f) {
        try {
            List<String> s = f.get();
            return s == null || s.isEmpty() ? List.of("-") : s;
        } catch (RuntimeException e) {
            log.info("山寨季仪表盘：一项取不到（显示\"-\"）：{}", e.toString());
            return List.of("-");
        }
    }

    static List<String> indicators(AltEarlySignals.Status st) {
        List<String> out = new ArrayList<>();
        if (st == null) {
            out.add("2. 5 个前兆指标：-（" + NOTE + "）");
            for (int k = 0; k < 5; k++) out.add("   - " + (char) ('a' + k) + " " + AltEarlySignals.NAMES[k] + "：-");
            return out;
        }
        int known = 0;
        for (int k = 0; k < 5; k++) if (st.known(k)) known++;
        String head = String.format(Locale.ROOT, "2. 5 个前兆指标：亮 %d / 5%s（埋伏第一档要 ≥ 2 个同时亮）。%s", st.count(),
                known < 5 ? "（" + (5 - known) + " 项缺数据）" : "", NOTE);
        out.add(head);
        out.add(item(st, 0, String.format(Locale.ROOT, "ETH/BTC %s，比 50 日线 %s（要 > 0），50 日线比 10 天前 %s（要 > 0，拐头向上）",
                num(st.ethBtc(), 5), pct(st.ethBtcVs50()), pct2(st.ethSmaSlope10()))));
        String armed = Double.isNaN(st.breadthLow60()) ? "-" : st.breadthLow60() < 0.3 ? "已满足" : "未满足";
        out.add(item(st, 1, String.format(Locale.ROOT, "前 40 里 %s 在 50 日线上方，过去 60 天最低 %s（先 < 30%%：%s），要升破 60%%%s；上次触发 %s",
                share(st.breadth()), share(st.breadthLow60()), armed,
                Double.isNaN(st.breadth()) || st.breadth() >= 0.6 ? "" : String.format(Locale.ROOT, "（还差 %.0f 个百分点）", (0.6 - st.breadth()) * 100),
                st.lastFireB() == null ? "-" : st.lastFireB().toString())));
        out.add(item(st, 2, Double.isNaN(st.rFromHigh60()) ? "-" : st.rFromHigh60() >= 0 ? "篮子/BTC 正在 60 日新高"
                : String.format(Locale.ROOT, "篮子/BTC 比 60 日最高 %s，还差 %s 创新高；上次新高 %s", pct(st.rFromHigh60()),
                pct(1 / (1 + st.rFromHigh60()) - 1), st.lastFireC() == null ? "-" : st.lastFireC().toString())));
        out.add(item(st, 3, String.format(Locale.ROOT, "稳定币总供应 30 日 %s（要 > +3%%）", pct(st.stable30()))));
        String e;
        if (!st.known(4)) {
            e = "-";
        } else {
            e = String.format(Locale.ROOT, "上次 BTC 90 日新高在 %d 天前，现价比那时 %s", st.sinceHigh(), pct(st.btcFromHigh()));
            if (st.sinceHigh() < 14) e += String.format(Locale.ROOT, "；再横盘 %d 天且不跌破新高的 90%% 就亮", 14 - st.sinceHigh());
            else if (st.sinceHigh() > 28 || st.btcFromHigh() < -0.1) e += "；要 BTC 再创 90 日新高，之后横盘 2～4 周";
        }
        out.add(item(st, 4, e));
        return out;
    }

    private static String item(AltEarlySignals.Status st, int k, String detail) {
        String flag = !st.known(k) ? "-" : st.on()[k] ? "亮" : (k == 1 && st.breadthLow60() < 0.3 ? "不亮（已预备）" : "不亮");
        return "   - " + (char) ('a' + k) + " " + AltEarlySignals.NAMES[k] + "：" + flag + (st.known(k) ? " — " + detail : "");
    }

    static String s2Line(AltEarlySignals.Status st) {
        if (st == null || !st.s2Known()) return "3. S2 开关：-";
        return String.format(Locale.ROOT, "3. S2 开关：%s — 篮子/BTC 比 50 日线 %s，篮子比 50 日线 %s（两个都 > 0 才开）", st.s2() ? "开" : "关",
                pct(st.rVs50()), pct(st.altVs50()));
    }

    static String rotationLine(AltEarlySignals.Panel p) {
        if (p == null || p.days() == 0) return "-";
        List<AltEarlySignals.Pick> picks = AltEarlySignals.rotation(p, p.days() - 1, AltEarlySignals.ROT_K);
        if (picks.isEmpty()) return "-";
        List<String> s = new ArrayList<>();
        for (AltEarlySignals.Pick x : picks) {
            s.add(String.format(Locale.ROOT, "%s（21 日 %s，分数 %.2f）", x.symbol().replace("USDT", ""), pct(x.ret21()), x.score()));
        }
        return String.join("、", s);
    }

    /** 板块：Binance 日线池内属于该板块、7 日和 21 日收益都算得出的币 ≥ 3 个；按 7 日、21 日收益排名之和排序，取前 3。 */
    List<String> sectorLines(AltEarlySignals.Panel p) {
        return sectorLines(p, research.sectorsBySymbol(Venue.BINANCE));
    }

    static List<String> sectorLines(AltEarlySignals.Panel p, Map<String, Set<String>> sectorsBySymbol) {
        int d = p.days() - 1;
        Map<String, List<double[]>> members = new TreeMap<>();
        Map<String, List<String>> names = new TreeMap<>();
        for (int i = 0; i < p.syms.length; i++) {
            if (!p.alt[i]) continue;
            Set<String> sec = sectorsBySymbol.get(p.syms[i]);
            if (sec == null) continue;
            double r7 = p.ret(i, d, 7), r21 = p.ret(i, d, 21);
            if (Double.isNaN(r7) || Double.isNaN(r21)) continue;
            for (String s : sec) {
                members.computeIfAbsent(s, k -> new ArrayList<>()).add(new double[]{r7, r21});
                names.computeIfAbsent(s, k -> new ArrayList<>()).add(p.syms[i]);
            }
        }
        record Sec(String id, int n, double r7, double r21, List<String> lead) {
        }
        List<Sec> secs = new ArrayList<>();
        for (var e : members.entrySet()) {
            List<double[]> m = e.getValue();
            if (m.size() < MIN_SECTOR) continue;
            double a = 0, b = 0;
            for (double[] x : m) {
                a += x[0];
                b += x[1];
            }
            List<String> nm = names.get(e.getKey());
            List<Integer> order = new ArrayList<>();
            for (int j = 0; j < m.size(); j++) order.add(j);
            order.sort((x, y) -> Double.compare(m.get(y)[1], m.get(x)[1]));
            List<String> lead = new ArrayList<>();
            for (int j = 0; j < Math.min(2, order.size()); j++) {
                int o = order.get(j);
                lead.add(String.format(Locale.ROOT, "%s（21 日 %s）", nm.get(o).replace("USDT", ""), pct(m.get(o)[1])));
            }
            secs.add(new Sec(e.getKey(), m.size(), a / m.size(), b / m.size(), lead));
        }
        if (secs.isEmpty()) return List.of();
        Map<String, Integer> rank = new LinkedHashMap<>();
        List<Sec> by7 = new ArrayList<>(secs);
        by7.sort(Comparator.comparingDouble(Sec::r7).reversed());
        List<Sec> by21 = new ArrayList<>(secs);
        by21.sort(Comparator.comparingDouble(Sec::r21).reversed());
        for (int j = 0; j < secs.size(); j++) {
            rank.merge(by7.get(j).id(), j, Integer::sum);
            rank.merge(by21.get(j).id(), j, Integer::sum);
        }
        secs.sort(Comparator.<Sec>comparingInt(x -> rank.get(x.id())).thenComparing(Comparator.comparingDouble(Sec::r21).reversed()));
        List<String> out = new ArrayList<>();
        for (Sec x : secs.subList(0, Math.min(3, secs.size()))) {
            out.add(String.format(Locale.ROOT, "%s（%d 个币）：7 日 %s，21 日 %s；龙头 %s", SECTOR_NAMES.getOrDefault(x.id(), x.id()), x.n(),
                    pct(x.r7()), pct(x.r21()), String.join("、", x.lead())));
        }
        return out;
    }

    // ------------------------------------------------------------------ data

    /** 已收盘日线 → 截止 lastDay 的 days 天公共日历；没有 BTC 时返回 null。 */
    static AltEarlySignals.Panel panel(Map<String, List<Kline>> bars, LocalDate lastDay, int days) {
        if (bars == null || bars.isEmpty() || !bars.containsKey(AltEarlySignals.BTC)) return null;
        LocalDate start = lastDay.minusDays(days - 1L);
        long startDay = start.toEpochDay();
        String[] syms = bars.keySet().stream().sorted().toArray(String[]::new);
        double[][] c = new double[syms.length][days], v = new double[syms.length][days];
        for (int i = 0; i < syms.length; i++) {
            java.util.Arrays.fill(c[i], Double.NaN);
            for (Kline k : bars.get(syms[i])) {
                long d = Math.floorDiv(k.openTime(), 86_400_000L) - startDay;
                if (d < 0 || d >= days || !(k.close() > 0)) continue;
                c[i][(int) d] = k.close();
                v[i][(int) d] = k.quoteVolume();
            }
        }
        return new AltEarlySignals.Panel(start, syms, c, v, Set.of());
    }

    /** Bybit 全部山寨里 24h 成交额前 60 + BTC 的已收盘日线（每个 1 次请求）；交易所未启用或取不到 BTC 时 null。 */
    AltEarlySignals.Panel bybitPanel(LocalDate day) {
        VenueMarket m = markets.get(Venue.BYBIT);
        if (m == null) return null;
        Set<String> blocked = new HashSet<>(research.excludedSymbols(Venue.BYBIT));
        blocked.addAll(research.tokenizedSymbols(Venue.BYBIT));
        List<Ticker24h> pool = filter.select(m.directory().all(), m.tickers().tickers24h(), Set.of(), blocked).stream()
                .filter(t -> !t.symbol().equals(UniverseFilter.BTC))
                .sorted(Comparator.comparingDouble(Ticker24h::quoteVolume).reversed()).limit(BYBIT_CANDIDATES).toList();
        long cutoff = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Map<String, List<Kline>> bars = new LinkedHashMap<>();
        List<String> want = new ArrayList<>();
        want.add(UniverseFilter.BTC);
        pool.forEach(t -> want.add(t.symbol()));
        for (String s : want) {
            try {
                List<Kline> k = m.source().klines(s, "1d", null, BYBIT_DAYS + 1).stream().filter(x -> x.closeTime() < cutoff).toList();
                if (!k.isEmpty()) bars.put(s, k);
            } catch (RuntimeException e) {
                log.debug("仪表盘 Bybit {} 日线获取失败：{}", s, e.getMessage());
            }
        }
        return panel(bars, day, BYBIT_DAYS);
    }

    /**
     * 稳定币总供应（epochDay → 美元）。库里已有收盘那天的值就不请求；否则每个 UTC 日最多请求 2 次，
     * 429 / 失败后当天剩余次数作废（显示"-"）。
     */
    Map<Long, Double> stableSeries(LocalDate day, Instant now) {
        String today = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
        Optional<String> max = jdbc.sql("SELECT MAX(day) FROM stablecoin_supply").query(String.class).optional();
        boolean fresh = max.map(LocalDate::parse).filter(d -> !d.isBefore(day)).isPresent();
        if (!fresh) {
            String[] a = state.get(STABLE_ATTEMPTS).orElse("").split(":");
            int used = a.length == 2 && a[0].equals(today) ? Integer.parseInt(a[1]) : 0;
            if (used < 2) {
                state.put(STABLE_ATTEMPTS, today + ":" + (used + 1));
                try {
                    Map<Long, Double> all = stablecoins.totals();
                    long keep = day.toEpochDay() - 400;
                    for (var e : all.entrySet()) {
                        if (e.getKey() < keep) continue;
                        jdbc.sql("INSERT OR REPLACE INTO stablecoin_supply(day, total_usd, updated_at) VALUES (:d, :v, :t)")
                                .param("d", LocalDate.ofEpochDay(e.getKey()).toString()).param("v", e.getValue()).param("t", now.toString())
                                .update();
                    }
                } catch (RuntimeException e) {
                    state.put(STABLE_ATTEMPTS, today + ":2"); // 当天不再请求
                    log.info("山寨季仪表盘：稳定币数据这次没取到（显示\"-\"，明天再试）：{}", e.getMessage());
                }
            }
        }
        Map<Long, Double> out = new TreeMap<>();
        jdbc.sql("SELECT day, total_usd FROM stablecoin_supply")
                .query((rs, n) -> out.put(LocalDate.parse(rs.getString(1)).toEpochDay(), rs.getDouble(2))).list();
        return out;
    }

    private void save(LocalDate day, String body, Instant now) {
        Integer lit = null, s2 = null;
        int known = 0;
        for (String l : body.split("\n")) {
            if (l.startsWith("2. 5 个前兆指标：亮 ")) lit = Integer.parseInt(l.substring("2. 5 个前兆指标：亮 ".length()).split(" ")[0]);
            if (l.startsWith("   - ") && l.matches("   - [a-e] .*") && !l.endsWith("：-")) known++;
            if (l.startsWith("3. S2 开关：开")) s2 = 1;
            if (l.startsWith("3. S2 开关：关")) s2 = 0;
        }
        jdbc.sql("INSERT OR REPLACE INTO alt_dashboard_daily(day, lit, known, s2, body, created_at) VALUES (:d, :l, :k, :s, :b, :t)")
                .param("d", day.toString()).param("l", lit).param("k", known).param("s", s2).param("b", body).param("t", now.toString())
                .update();
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("alt-dashboard-" + day + ".md"), body, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            log.info("山寨季仪表盘文件没写成（库里已存）：{}", e.getMessage());
        }
    }

    static String pct(double x) {
        return Double.isNaN(x) || Double.isInfinite(x) ? "-" : String.format(Locale.ROOT, "%+.1f%%", 100 * x);
    }

    static String pct2(double x) {
        return Double.isNaN(x) || Double.isInfinite(x) ? "-" : String.format(Locale.ROOT, "%+.2f%%", 100 * x);
    }

    static String share(double x) {
        return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.0f%%", 100 * x);
    }

    static String num(double x, int digits) {
        return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%." + digits + "f", x);
    }
}
