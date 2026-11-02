package com.trendpilot.backtest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bybit 现货历史数据（V6 Bybit 回测，2026-11-02）：Bybit 公开数据站 public.bybit.com/spot/ 只有逐笔成交（早期按月、之后按天一个
 * csv.gz），没有 K 线包；v5 kline 接口在 GitHub Actions 所在地区返回 403。这里把逐笔成交聚合成 15m K 线，写成和
 * data.binance.vision 相同的月度 zip 布局（{@link Archive} 直接读），没有数据的月份写 .none 标记，回测不会再去 Binance 下载。
 * <pre>
 * java -cp ... com.trendpilot.backtest.BybitArchive 输出目录 起始月 结束月 线程数
 * </pre>
 * 字段：taker 买入量按成交方向 side = buy 计；成交额 = 价格 × 数量。只读公开数据，同时最多 线程数 个下载。
 */
public final class BybitArchive {

    static final String BASE = "https://public.bybit.com/spot/";
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
    private static final Pattern MONTHLY = Pattern.compile("^([A-Z0-9]+)-(\\d{4}-\\d{2})\\.csv\\.gz$");
    private static final Pattern DAILY = Pattern.compile("^([A-Z0-9]+)_(\\d{4}-\\d{2})-\\d{2}\\.csv\\.gz$");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        YearMonth from = YearMonth.parse(args[1]);
        YearMonth to = YearMonth.parse(args[2]);
        int threads = Integer.parseInt(args[3]);
        new BybitArchive().run(out, from, to, threads);
    }

    void run(Path out, YearMonth from, YearMonth to, int threads) throws Exception {
        List<String> symbols = new ArrayList<>();
        for (String h : hrefs(get(BASE))) {
            String s = h.endsWith("/") ? h.substring(0, h.length() - 1) : h;
            if (s.matches("[A-Z0-9]+USDT")) symbols.add(s);
        }
        System.out.println("Bybit 现货 USDT 交易对 " + symbols.size() + " 个");
        TreeSet<String> withData = new TreeSet<>();
        AtomicInteger done = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    try {
                        if (symbol(out, s, from, to)) {
                            synchronized (withData) {
                                withData.add(s);
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("跳过 " + s + "：" + e.getMessage());
                    }
                    int n = done.incrementAndGet();
                    if (n % 25 == 0) System.out.println("进度 " + n + "/" + symbols.size());
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        Files.createDirectories(out);
        Files.write(out.resolve("symbols.txt"), withData);
        System.out.println("完成：有数据的交易对 " + withData.size() + " 个");
    }

    /** 一个交易对：按月聚合，已有的月份跳过。@return 区间内是否有任何数据 */
    boolean symbol(Path out, String s, YearMonth from, YearMonth to) throws IOException, InterruptedException {
        Map<YearMonth, List<String>> files = new TreeMap<>();
        for (String f : hrefs(get(BASE + s + "/"))) {
            Matcher m = MONTHLY.matcher(f);
            Matcher d = DAILY.matcher(f);
            if (m.matches()) files.computeIfAbsent(YearMonth.parse(m.group(2)), k -> new ArrayList<>()).add(0, f);
            else if (d.matches()) files.computeIfAbsent(YearMonth.parse(d.group(2)), k -> new ArrayList<>()).add(f);
        }
        boolean any = false;
        for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) {
            String rel = "data/spot/monthly/klines/" + s + "/15m/" + s + "-15m-" + ym + ".zip";
            Path zip = out.resolve(rel);
            Path none = out.resolve(rel + ".none");
            if (Files.exists(zip)) {
                any = true;
                continue;
            }
            if (Files.exists(none)) continue;
            Files.createDirectories(zip.getParent());
            List<String> fl = files.getOrDefault(ym, List.of());
            // 同一个月既有月度包又有日度包时只用月度包
            if (!fl.isEmpty() && MONTHLY.matcher(fl.getFirst()).matches()) fl = List.of(fl.getFirst());
            Bars bars = new Bars();
            for (String f : fl) {
                try (InputStream in = open(BASE + s + "/" + f)) {
                    bars.add(new BufferedReader(new InputStreamReader(new GZIPInputStream(in, 1 << 16), StandardCharsets.US_ASCII), 1 << 16));
                }
            }
            if (bars.isEmpty()) {
                Files.writeString(none, "");
                continue;
            }
            Path part = zip.resolveSibling(zip.getFileName() + ".part");
            try (OutputStream o = Files.newOutputStream(part); ZipOutputStream z = new ZipOutputStream(o)) {
                z.putNextEntry(new ZipEntry(s + "-15m-" + ym + ".csv"));
                z.write(bars.csv().getBytes(StandardCharsets.US_ASCII));
                z.closeEntry();
            }
            Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
            any = true;
        }
        return any;
    }

    /** 逐笔成交 → 15m K 线（Binance kline CSV 的 12 列）。 */
    static final class Bars {
        /** open_time → {open, high, low, close, volume, quote, trades, takerBase, takerQuote, firstTs, lastTs} */
        final TreeMap<Long, double[]> bars = new TreeMap<>();

        boolean isEmpty() {
            return bars.isEmpty();
        }

        /** 读一个逐笔成交 CSV（带表头；列名 timestamp、price、volume 或 size、side，顺序不限；时间可以是毫秒或秒）。 */
        void add(BufferedReader r) throws IOException {
            String header = r.readLine();
            if (header == null) return;
            String[] h = header.toLowerCase(Locale.ROOT).split(",");
            int ts = -1, px = -1, qty = -1, side = -1;
            for (int i = 0; i < h.length; i++) {
                switch (h[i].trim()) {
                    case "timestamp" -> ts = i;
                    case "price" -> px = i;
                    case "volume", "size" -> qty = i;
                    case "side" -> side = i;
                    default -> { }
                }
            }
            if (ts < 0 || px < 0 || qty < 0) throw new IOException("看不懂的表头：" + header);
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",");
                if (f.length <= Math.max(ts, Math.max(px, qty))) continue;
                double t = Double.parseDouble(f[ts]);
                long ms = t < 1e11 ? (long) (t * 1000) : (long) t;
                double p = Double.parseDouble(f[px]);
                double q = Double.parseDouble(f[qty]);
                if (!(p > 0) || !(q > 0)) continue;
                boolean buy = side >= 0 && f.length > side && f[side].trim().equalsIgnoreCase("buy");
                trade(ms, p, q, buy);
            }
        }

        void trade(long ms, double p, double q, boolean takerBuy) {
            long open = ms - Math.floorMod(ms, Series.Q);
            double[] b = bars.get(open);
            if (b == null) {
                bars.put(open, new double[]{p, p, p, p, q, p * q, 1, takerBuy ? q : 0, takerBuy ? p * q : 0, ms, ms});
                return;
            }
            if (ms < b[9]) {
                b[0] = p;
                b[9] = ms;
            }
            if (ms >= b[10]) {
                b[3] = p;
                b[10] = ms;
            }
            b[1] = Math.max(b[1], p);
            b[2] = Math.min(b[2], p);
            b[4] += q;
            b[5] += p * q;
            b[6]++;
            if (takerBuy) {
                b[7] += q;
                b[8] += p * q;
            }
        }

        String csv() {
            StringBuilder sb = new StringBuilder();
            for (var e : bars.entrySet()) {
                double[] b = e.getValue();
                sb.append(e.getKey()).append(',').append(num(b[0])).append(',').append(num(b[1])).append(',').append(num(b[2]))
                        .append(',').append(num(b[3])).append(',').append(num(b[4])).append(',').append(e.getKey() + Series.Q - 1)
                        .append(',').append(num(b[5])).append(',').append((long) b[6]).append(',').append(num(b[7]))
                        .append(',').append(num(b[8])).append(",0\n");
            }
            return sb.toString();
        }

        private static String num(double v) {
            return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
        }
    }

    static List<String> hrefs(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = HREF.matcher(html);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private String get(String url) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return r.body();
                if (attempt >= 4) throw new IOException(url + " HTTP " + r.statusCode());
            } catch (IOException e) {
                if (attempt >= 4) throw e;
            }
            Thread.sleep(1000L << attempt);
        }
    }

    private InputStream open(String url) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() == 200) return r.body();
                r.body().close();
                if (attempt >= 4) throw new IOException(url + " HTTP " + r.statusCode());
            } catch (IOException e) {
                if (attempt >= 4) throw e;
            }
            Thread.sleep(1000L << attempt);
        }
    }

    static long monthStart(YearMonth m) {
        return LocalDate.of(m.getYear(), m.getMonth(), 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
