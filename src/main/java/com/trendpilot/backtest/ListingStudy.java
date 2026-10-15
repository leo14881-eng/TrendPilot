package com.trendpilot.backtest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 候选 B：别的交易所（Upbit 韩元市场）宣布上线某个币，而这个币已经在 Binance 现货交易，看到公告后在 Binance 买入能不能赚。
 * 公告时间来自 Upbit 公告接口（免费、公开）；价格用 data.binance.vision 的 1 分钟 K 线。
 * 买入：公告发出后延迟 1 / 5 / 15 分钟，那一分钟 K 线的开盘价（看到公告再下单，最快也要这么久）。
 * 卖出：持有 1h / 4h / 24h / 72h 后那一分钟的开盘价。成本：来回手续费 0.2% + 冲击（按 24h 成交额分档；公告后行情剧烈，另列冲击 ×3 的结果）。
 * 对照：同一个币在公告前第 8~30 天、同一时刻买入、同样持有时长的平均收益（排除这个币自己的常态涨跌）。
 */
public final class ListingStudy {

    static final int[] DELAYS = {1, 5, 15};
    static final int[] HOLDS = {60, 240, 1440, 4320};
    static final String[] HOLD_NAMES = {"1 小时", "4 小时", "24 小时", "3 天"};
    static final double FEE = 0.001;

    record Event(String source, long id, Instant at, String title, String ticker) {
    }

    /** 一笔：事件、延迟、持有 → 净收益%、毛收益%、对照毛收益%、冲击×3 净收益%。 */
    record Row(Event e, int delay, int hold, double net, double gross, double control, double net3, double preMove, double entry) {
    }

    private static final Pattern PAREN = Pattern.compile("\\(([A-Z0-9, ]{2,60})\\)");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Path dir;
    final List<Event> events = new ArrayList<>();
    final List<Row> rows = new ArrayList<>();
    final List<String> skipped = new ArrayList<>();

    ListingStudy(Path dir) {
        this.dir = dir;
    }

    /** Upbit 交易类公告，按页取到没有为止（缓存原始 JSON）。 */
    void fetchUpbit() throws IOException, InterruptedException {
        Path cache = dir.resolve("upbit");
        Files.createDirectories(cache);
        for (int page = 1; page <= 400; page++) {
            Path f = cache.resolve("trade-" + page + ".json");
            String body;
            if (Files.exists(f) && page > 3) {
                body = Files.readString(f);
            } else {
                body = get("https://api-manager.upbit.com/api/v1/announcements?os=web&page=" + page + "&per_page=20&category=trade");
                if (body == null) break;
                Files.writeString(f, body);
                Thread.sleep(300); // 对公开接口保持克制
            }
            int n = parseUpbit(body, events);
            if (n == 0) break;
        }
        System.out.println("Upbit 公告：上线类事件 " + events.size() + " 个");
    }

    /** 解析一页，把"韩元市场上线"的公告拆成每个币一个事件。返回这一页的公告总数（0 = 没有了）。 */
    static int parseUpbit(String body, List<Event> out) throws IOException {
        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode list = root.path("data").path("notices");
        if (!list.isArray()) list = root.path("data").path("list");
        if (!list.isArray()) return 0;
        for (JsonNode n : list) {
            String title = n.path("title").asText("");
            String ts = firstText(n, "first_listed_at", "listed_at", "created_at");
            if (ts == null || !isKrwListing(title)) continue;
            Instant at = OffsetDateTime.parse(ts).toInstant();
            for (String t : tickers(title)) out.add(new Event("Upbit", n.path("id").asLong(), at, title, t));
        }
        return list.size();
    }

    private static String firstText(JsonNode n, String... keys) {
        for (String k : keys) if (n.hasNonNull(k) && !n.get(k).asText().isBlank()) return n.get(k).asText();
        return null;
    }

    /** 韩元市场新增币的公告（"원화 마켓" / "KRW 마켓" + "추가"），排除只在 BTC/USDT 市场的。 */
    static boolean isKrwListing(String title) {
        boolean krw = title.contains("원화") || title.contains("KRW");
        boolean add = title.contains("추가") || title.contains("상장");
        boolean other = title.contains("유의") || title.contains("종료") || title.contains("연기") || title.contains("거래지원 종료");
        return krw && add && !other;
    }

    static List<String> tickers(String title) {
        List<String> out = new ArrayList<>();
        Matcher m = PAREN.matcher(title);
        while (m.find()) {
            for (String p : m.group(1).split(",")) {
                String t = p.trim();
                if (t.matches("[A-Z0-9]{2,15}") && !t.equals("KRW") && !t.equals("BTC") && !t.equals("USDT")) out.add(t);
            }
        }
        return out;
    }

    private String get(String url) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= 4; attempt++) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json").header("User-Agent", "Mozilla/5.0 (research backtest)").build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) return r.body();
            if (r.statusCode() == 404) return null;
            System.out.println("Upbit 接口 HTTP " + r.statusCode() + "，重试");
            Thread.sleep(2000L << attempt);
        }
        return null;
    }

    /** 对每个事件：取 Binance 1 分钟 K 线（公告前 31 天到后 4 天），算各延迟、各持有时长的收益。 */
    void run(Set<String> binanceSymbols, LocalDate from, LocalDate to) throws IOException, InterruptedException {
        Path kdir = dir.resolve("klines");
        Archive oneMinute = new Archive(kdir);
        Map<String, Event> seen = new LinkedHashMap<>();
        for (Event e : events) {
            LocalDate day = e.at().atZone(ZoneOffset.UTC).toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) continue;
            seen.putIfAbsent(e.ticker() + "@" + e.at(), e);
        }
        for (Event e : seen.values()) {
            String sym = e.ticker() + "USDT";
            if (!binanceSymbols.contains(sym)) {
                skipped.add(e.ticker() + "：Binance 没有 " + sym);
                continue;
            }
            LocalDate d0 = e.at().atZone(ZoneOffset.UTC).toLocalDate();
            Map<Long, double[]> bars = new HashMap<>();
            Set<String> months = new java.util.TreeSet<>();
            for (LocalDate d = d0.minusDays(31); !d.isAfter(d0.plusDays(4)); d = d.plusDays(1)) {
                String rel = "data/spot/daily/klines/" + sym + "/1m/" + sym + "-1m-" + d + ".zip";
                oneMinute.fetch(rel);
                if (Files.exists(kdir.resolve(rel))) readMinutes(kdir.resolve(rel), bars);
                else months.add(d.toString().substring(0, 7));
            }
            // 早年的日度包不全时，用月度包补
            for (String m : months) {
                String rel = "data/spot/monthly/klines/" + sym + "/1m/" + sym + "-1m-" + m + ".zip";
                oneMinute.fetch(rel);
                readMinutes(kdir.resolve(rel), bars);
            }
            long t0 = e.at().toEpochMilli() / 60_000 * 60_000;
            // 公告前一天的成交额，用来估冲击；公告前一天没在交易就跳过（不是"已经在 Binance 上"）
            double vol = 0;
            int have = 0;
            for (long t = t0 - 86_400_000L; t < t0; t += 60_000) {
                double[] b = bars.get(t);
                if (b != null) {
                    vol += b[4];
                    have++;
                }
            }
            if (have < 600) {
                skipped.add(e.ticker() + "：公告前 24 小时 Binance 数据不足");
                continue;
            }
            double[] pre = bars.get(t0 - 60 * 60_000L);
            double[] at0 = bars.get(t0);
            double preMove = pre == null || at0 == null ? Double.NaN : (at0[0] / pre[0] - 1) * 100;
            double imp = Costs.impact(vol, 1000);
            for (int delay : DELAYS) {
                for (int hold : HOLDS) {
                    double[] in = open(bars, t0 + delay * 60_000L);
                    double[] out = open(bars, t0 + (delay + hold) * 60_000L);
                    if (in == null || out == null) continue;
                    double gross = (out[0] / in[0] - 1) * 100;
                    double net = (out[0] * (1 - imp) * (1 - FEE) / (in[0] * (1 + imp) * (1 + FEE)) - 1) * 100;
                    double net3 = (out[0] * (1 - 3 * imp) * (1 - FEE) / (in[0] * (1 + 3 * imp) * (1 + FEE)) - 1) * 100;
                    double control = control(bars, t0 + delay * 60_000L, hold);
                    rows.add(new Row(e, delay, hold, net, gross, control, net3, preMove, in[0]));
                }
            }
        }
        System.out.println("上币事件可用 " + rows.stream().map(r -> r.e()).distinct().count() + " 个，跳过 " + skipped.size() + " 个");
    }

    /** 同一个币在公告前第 8~30 天、同一时刻买入、持有同样时长的平均毛收益%。 */
    static double control(Map<Long, double[]> bars, long entry, int hold) {
        double s = 0;
        int n = 0;
        for (int k = 8; k <= 30; k++) {
            long t = entry - k * 86_400_000L;
            double[] a = bars.get(t), b = bars.get(t + hold * 60_000L);
            if (a == null || b == null) continue;
            s += (b[0] / a[0] - 1) * 100;
            n++;
        }
        return n < 10 ? Double.NaN : s / n;
    }

    /** t 时刻（或之后 5 分钟内第一根）1 分钟 K 线：{开, 高, 低, 收, 成交额}。 */
    static double[] open(Map<Long, double[]> bars, long t) {
        for (int k = 0; k < 5; k++) {
            double[] b = bars.get(t + k * 60_000L);
            if (b != null) return b;
        }
        return null;
    }

    static void readMinutes(Path zip, Map<Long, double[]> bars) throws IOException {
        if (!Files.exists(zip)) return;
        try (var in = Files.newInputStream(zip); var z = new java.util.zip.ZipInputStream(in)) {
            if (z.getNextEntry() == null) return;
            var r = new java.io.BufferedReader(new java.io.InputStreamReader(z, java.nio.charset.StandardCharsets.US_ASCII));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;
                String[] f = line.split(",", 9);
                long t = Long.parseLong(f[0]);
                if (t > 100_000_000_000_000L) t /= 1000;
                bars.put(t, new double[]{Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[7])});
            }
        }
    }

    /** 按笔平均和按天聚类的 95% 区间（同一天多个币一起上线时互相相关）。 */
    static double[] ci(List<Row> rs, java.util.function.ToDoubleFunction<Row> f) {
        List<double[]> v = new ArrayList<>();
        for (Row r : rs) {
            double x = f.applyAsDouble(r);
            if (!Double.isNaN(x)) v.add(new double[]{x, r.e().at().getEpochSecond() / 86400});
        }
        return PumpStudy.clusterCi(v);
    }

    public String markdown() {
        StringBuilder b = new StringBuilder("## 候选 B：Upbit 韩元市场上币公告 → 在 Binance 现货买入\n\n");
        if (events.isEmpty()) return b.append("**没有取到 Upbit 公告（接口不通或格式变了），这一项没有结果。**\n\n").toString();
        b.append(String.format(Locale.ROOT, "Upbit 韩元市场上线公告拆成每个币一个事件，共 %d 个（最早 %s，最晚 %s）。%n%n", events.size(),
                events.stream().map(Event::at).min(Instant::compareTo).orElseThrow(), events.stream().map(Event::at).max(Instant::compareTo).orElseThrow()));
        long ev = rows.stream().map(Row::e).distinct().count();
        b.append(String.format(Locale.ROOT, "公告里已在 Binance 交易的币：%d 个事件（跳过 %d 个：Binance 没有这个币，或公告前 24 小时没在交易）。", ev, skipped.size()));
        b.append("买入价 = 公告后第 N 分钟那根 1 分钟 K 线的开盘价；卖出价 = 再过持有时长那一分钟的开盘价。")
                .append("「对照」= 同一个币在公告前第 8~30 天同一时刻买、持有同样时长的平均毛收益；「超额」= 毛收益 − 对照。")
                .append("区间围绕每笔平均，按公告日期聚类。真实情况下公告后几秒内价格可能已经跳很多，1 分钟延迟也偏乐观。\n\n");
        double[] pm = ci(rows.stream().filter(r -> r.delay() == 1 && r.hold() == 60).toList(), Row::preMove);
        b.append(String.format(Locale.ROOT, "公告前 1 小时到公告那一分钟，价格平均已经变动 %.2f%%（%.2f ~ %.2f），这部分买不到。%n%n", pm[0], pm[1], pm[2]));
        b.append("| 延迟 | 持有 | 笔数 | 每笔净收益% | 95% 区间 | 冲击×3 净收益% | 毛收益% | 对照% | 超额% | 超额 95% 区间 | 中位数净% | 赚钱比例 |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (int delay : DELAYS) {
            for (int h = 0; h < HOLDS.length; h++) {
                int hold = HOLDS[h];
                List<Row> rs = rows.stream().filter(r -> r.delay() == delay && r.hold() == hold).toList();
                if (rs.isEmpty()) continue;
                double[] net = ci(rs, Row::net), n3 = ci(rs, Row::net3), g = ci(rs, Row::gross), c = ci(rs, Row::control);
                double[] ex = ci(rs, r -> r.gross() - r.control());
                double[] v = rs.stream().mapToDouble(Row::net).sorted().toArray();
                double win = Arrays.stream(v).filter(x -> x > 0).count() * 100.0 / v.length;
                b.append(String.format(Locale.ROOT, "| %d 分钟 | %s | %d | %.2f | %.2f ~ %.2f | %.2f | %.2f | %.2f | %.2f | %.2f ~ %.2f | %.2f | %.0f%% |%n",
                        delay, HOLD_NAMES[h], rs.size(), net[0], net[1], net[2], n3[0], g[0], c[0], ex[0], ex[1], ex[2], v[v.length / 2], win));
            }
        }
        b.append("\n### 按年份（延迟 1 分钟，持有 24 小时，每笔净收益% / 笔数）\n\n");
        Map<Integer, List<Row>> byYear = new java.util.TreeMap<>();
        for (Row r : rows) if (r.delay() == 1 && r.hold() == 1440) byYear.computeIfAbsent(r.e().at().atZone(ZoneOffset.UTC).getYear(), k -> new ArrayList<>()).add(r);
        for (var e : byYear.entrySet()) {
            double m = e.getValue().stream().mapToDouble(Row::net).average().orElse(0);
            b.append(String.format(Locale.ROOT, "- %d：%.2f%%（%d 笔）%n", e.getKey(), m, e.getValue().size()));
        }
        return b.append('\n').toString();
    }

    public void writeCsv(Path out) throws IOException {
        List<String> l = new ArrayList<>();
        for (Row r : rows) {
            l.add(String.format(Locale.ROOT, "%s,%d,%s,%s,%d,%d,%.8f,%.4f,%.4f,%.4f,%.4f,%.4f,\"%s\"", r.e().source(), r.e().id(), r.e().at(), r.e().ticker(), r.delay(), r.hold(),
                    r.entry(), r.net(), r.gross(), r.control(), r.net3(), r.preMove(), r.e().title().replace("\"", "'")));
        }
        Files.writeString(out.resolve("listing-trades.csv"), "source,notice_id,announced_utc,ticker,delay_min,hold_min,entry_price,net_pct,gross_pct,control_pct,net_impact3x_pct,pre_1h_move_pct,title\n" + String.join("\n", l) + "\n");
        Files.writeString(out.resolve("listing-skipped.txt"), String.join("\n", skipped) + "\n");
    }
}
