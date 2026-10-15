package com.trendpilot.backtest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 币安公开数据站（data.binance.vision）的 15m K 线压缩包：月度包 + 当月的日度包。
 * 不是交易接口、不占 API 限流；只下载一次，存在本地目录，之后直接读。
 * 2025 年起现货包的时间戳是微秒，读入时统一换成毫秒。
 */
public final class Archive {

    static final String BASE = "https://data.binance.vision/";
    static final String LIST = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision";
    private static final Pattern PREFIX = Pattern.compile("<Prefix>data/spot/monthly/klines/([A-Z0-9]+)/</Prefix>");
    private static final Pattern NEXT = Pattern.compile("<NextMarker>([^<]+)</NextMarker>");

    private final Path dir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** 同时最多这么多个下载，对公开数据站保持克制。 */
    private final Semaphore gate = new Semaphore(16);

    public Archive(Path dir) {
        this.dir = dir;
    }

    /** 数据站上所有以 USDT 计价的现货交易对（含已下架的）。结果缓存到 symbols.txt。 */
    public List<String> usdtSymbols() throws IOException, InterruptedException {
        Path cache = dir.resolve("symbols.txt");
        if (Files.exists(cache)) return Files.readAllLines(cache);
        TreeSet<String> out = new TreeSet<>();
        String marker = "";
        for (int page = 0; page < 50; page++) {
            String url = LIST + "?delimiter=/&prefix=data/spot/monthly/klines/" + (marker.isEmpty() ? "" : "&marker=" + marker);
            String xml = get(url);
            Matcher m = PREFIX.matcher(xml);
            while (m.find()) if (m.group(1).endsWith("USDT")) out.add(m.group(1));
            Matcher n = NEXT.matcher(xml);
            if (!xml.contains("<IsTruncated>true</IsTruncated>") || !n.find()) break;
            marker = n.group(1);
        }
        Files.createDirectories(dir);
        Files.write(cache, out);
        return new ArrayList<>(out);
    }

    /** 下载 [from, to] 的月度包，以及 dailyMonth（可空）当月 1 日到 lastDay 的日度包。不存在的包跳过。 */
    public void download(List<String> symbols, YearMonth from, YearMonth to, LocalDate lastDay) throws InterruptedException {
        AtomicInteger done = new AtomicInteger();
        AtomicInteger fetched = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
                        if (fetch(monthlyPath(s, m))) fetched.incrementAndGet();
                    }
                    if (lastDay != null) {
                        for (LocalDate d = lastDay.withDayOfMonth(1); !d.isAfter(lastDay); d = d.plusDays(1)) {
                            if (fetch(dailyPath(s, d))) fetched.incrementAndGet();
                        }
                    }
                    int n = done.incrementAndGet();
                    if (n % 100 == 0) System.out.println("下载进度 " + n + "/" + symbols.size() + "，新下载 " + fetched.get());
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    System.out.println("下载失败：" + e.getMessage());
                }
            }
        }
        System.out.println("下载完成：" + symbols.size() + " 个交易对，新下载 " + fetched.get() + " 个包");
    }

    static String monthlyPath(String s, YearMonth m) {
        return "data/spot/monthly/klines/" + s + "/15m/" + s + "-15m-" + m + ".zip";
    }

    static String dailyPath(String s, LocalDate d) {
        return "data/spot/daily/klines/" + s + "/15m/" + s + "-15m-" + d + ".zip";
    }

    /** @return 是否新下载了；已有或不存在都返回 false（不存在记一个 .none 标记，不重复请求）。 */
    private boolean fetch(String rel) throws IOException, InterruptedException {
        Path target = dir.resolve(rel);
        Path none = dir.resolve(rel + ".none");
        if (Files.exists(target) || Files.exists(none)) return false;
        Files.createDirectories(target.getParent());
        for (int attempt = 1; ; attempt++) {
            gate.acquire();
            try {
                HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(BASE + rel)).timeout(Duration.ofSeconds(60)).build(),
                        HttpResponse.BodyHandlers.ofFile(target.resolveSibling(target.getFileName() + ".part")));
                if (r.statusCode() == 200) {
                    Files.move(r.body(), target, StandardCopyOption.REPLACE_EXISTING);
                    return true;
                }
                Files.deleteIfExists(r.body());
                if (r.statusCode() == 404 || r.statusCode() == 403) {
                    Files.writeString(none, "");
                    return false;
                }
                if (attempt >= 4) throw new IOException(rel + " HTTP " + r.statusCode());
            } catch (IOException e) {
                if (attempt >= 4) throw e;
            } finally {
                gate.release();
            }
            Thread.sleep(1000L << attempt);
        }
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

    /** 把一个交易对的全部包读进统一网格。没有任何数据时返回 null。 */
    public Series load(String symbol, long start, int size, YearMonth from, YearMonth to, LocalDate lastDay) throws IOException {
        Series s = new Series(symbol, start, size);
        boolean any = false;
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) any |= read(dir.resolve(monthlyPath(symbol, m)), s);
        if (lastDay != null) {
            for (LocalDate d = lastDay.withDayOfMonth(1); !d.isAfter(lastDay); d = d.plusDays(1)) {
                any |= read(dir.resolve(dailyPath(symbol, d)), s);
            }
        }
        return any ? s : null;
    }

    static boolean read(Path zip, Series s) throws IOException {
        if (!Files.exists(zip)) return false;
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e = z.getNextEntry();
            if (e == null) return false;
            return parse(new BufferedReader(new InputStreamReader(z, StandardCharsets.US_ASCII)), s);
        }
    }

    /** CSV：open_time, open, high, low, close, volume, close_time, quote_volume, ... 可能带表头。 */
    static boolean parse(BufferedReader r, Series s) throws IOException {
        boolean any = false;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;
            String[] f = line.split(",", 9);
            long t = Long.parseLong(f[0]);
            if (t > 100_000_000_000_000L) t /= 1000; // 微秒 → 毫秒
            long off = t - s.start;
            if (off < 0 || off % Series.Q != 0) continue;
            int i = (int) (off / Series.Q);
            double c = Double.parseDouble(f[4]);
            if (!(c > 0)) continue;
            s.set(i, Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]), c,
                    Double.parseDouble(f[7]));
            any = true;
        }
        return any;
    }
}
