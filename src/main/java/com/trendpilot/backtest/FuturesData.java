package com.trendpilot.backtest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 币安 U 本位永续合约的公开历史数据（data.binance.vision，只读，不是交易接口）：
 * 资金费率（月度包）和每 5 分钟的持仓量、多空比、主动买卖比（日度 metrics 包）。
 * 按现货交易对对齐到整点网格：第 h 格 = 这个小时内最后一个值；资金费率向后填充（最近一次结算的费率）。
 */
public final class FuturesData {

    private static final Pattern KEY = Pattern.compile("<Key>([^<]+\\.zip)</Key>");
    private static final Pattern NEXT = Pattern.compile("<NextMarker>([^<]+)</NextMarker>");
    private static final Pattern PREFIX = Pattern.compile("<Prefix>data/futures/um/monthly/fundingRate/([A-Z0-9]+)/</Prefix>");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一个币的合约数据（下标 = 整点格）。没有数据为 NaN。 */
    public static final class Fut {
        public final String perp;
        /** 最近一次结算的资金费率（%，每期）。 */
        public final float[] funding;
        /** 持仓价值（USDT）。 */
        public final float[] oiValue;
        /** 大户持仓多空比（sum_toptrader_long_short_ratio）。 */
        public final float[] topLs;
        /** 全部账户人数多空比（count_long_short_ratio）。 */
        public final float[] ls;
        /** 合约主动买 / 主动卖 成交量比（sum_taker_long_short_vol_ratio）。 */
        public final float[] takerLs;
        /** 估计的合约上线整点（第一次资金费结算前一个结算周期）；数据开始前就已上线为 -1。 */
        int listedHour = -1;
        private boolean seenFunding;

        Fut(String perp, int hours) {
            this.perp = perp;
            funding = nan(hours);
            oiValue = nan(hours);
            topLs = nan(hours);
            ls = nan(hours);
            takerLs = nan(hours);
        }

        public int listedHour() {
            return listedHour;
        }

        private static float[] nan(int n) {
            float[] a = new float[n];
            Arrays.fill(a, Float.NaN);
            return a;
        }
    }

    private final Archive archive;
    private final Path dir;
    private final long start;
    private final int hours;
    private final Map<String, Fut> bySpot = new ConcurrentHashMap<>();

    public FuturesData(Path dir, long start, int hours) {
        this.archive = new Archive(dir);
        this.dir = dir;
        this.start = start;
        this.hours = hours;
    }

    public Fut get(String spot) {
        return bySpot.get(spot);
    }

    public int size() {
        return bySpot.size();
    }

    /** 数据站上所有永续合约（含已下架）。缓存到 futures-symbols.txt。 */
    List<String> perps() throws IOException, InterruptedException {
        Path cache = dir.resolve("futures-symbols.txt");
        if (Files.exists(cache)) return Files.readAllLines(cache);
        TreeSet<String> out = new TreeSet<>();
        String marker = "";
        for (int page = 0; page < 50; page++) {
            String xml = archive.get(Archive.LIST + "?delimiter=/&prefix=data/futures/um/monthly/fundingRate/" + (marker.isEmpty() ? "" : "&marker=" + marker));
            Matcher m = PREFIX.matcher(xml);
            while (m.find()) out.add(m.group(1));
            Matcher n = NEXT.matcher(xml);
            if (!xml.contains("<IsTruncated>true</IsTruncated>") || !n.find()) break;
            marker = n.group(1);
        }
        Files.createDirectories(dir);
        Files.write(cache, out);
        return new ArrayList<>(out);
    }

    /** 现货 → 永续合约名：同名优先，其次 1000 / 1000000 倍合约（价格倍数不影响费率、持仓价值和比值）。 */
    static String perpFor(String spot, Set<String> perps) {
        if (perps.contains(spot)) return spot;
        if (perps.contains("1000" + spot)) return "1000" + spot;
        if (perps.contains("1000000" + spot)) return "1000000" + spot;
        return null;
    }

    /** 某个前缀下已有的 zip 文件（分页列出），只下载存在的包，避免大量 404。 */
    private List<String> list(String prefix) throws IOException, InterruptedException {
        Path cache = dir.resolve(prefix + ".list");
        if (Files.exists(cache)) return Files.readAllLines(cache);
        List<String> out = new ArrayList<>();
        String marker = "";
        for (int page = 0; page < 20; page++) {
            String xml = archive.get(Archive.LIST + "?prefix=" + prefix + (marker.isEmpty() ? "" : "&marker=" + marker));
            Matcher m = KEY.matcher(xml);
            String last = null;
            while (m.find()) {
                out.add(m.group(1));
                last = m.group(1);
            }
            if (!xml.contains("<IsTruncated>true</IsTruncated>") || last == null) break;
            Matcher n = NEXT.matcher(xml);
            marker = n.find() ? n.group(1) : last;
        }
        Files.createDirectories(cache.getParent());
        Files.write(cache, out);
        return out;
    }

    /**
     * 下载并读入：资金费率 [fundingFrom, to] 的月度包；metrics 从 metricsFrom 到 lastDay 的日度包。
     * 只下载数据站上实际存在的包。
     */
    public void load(Collection<String> spots, YearMonth fundingFrom, YearMonth to, LocalDate metricsFrom, LocalDate lastDay)
            throws IOException, InterruptedException {
        Set<String> perps = new java.util.HashSet<>(perps());
        AtomicInteger done = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String spot : spots) {
                String perp = perpFor(spot, perps);
                if (perp == null) continue;
                fs.add(pool.submit(() -> {
                    Fut f = new Fut(perp, hours);
                    List<String> fund = new ArrayList<>();
                    for (String k : list("data/futures/um/monthly/fundingRate/" + perp + "/")) {
                        String ym = k.substring(k.length() - 11, k.length() - 4);
                        YearMonth m = YearMonth.parse(ym);
                        if (!m.isBefore(fundingFrom) && !m.isAfter(to)) fund.add(k);
                    }
                    fund.sort(null);
                    for (String k : fund) {
                        archive.fetch(k);
                        readFunding(dir.resolve(k), f);
                    }
                    fillForward(f.funding, 9);
                    List<String> met = new ArrayList<>();
                    for (String k : list("data/futures/um/daily/metrics/" + perp + "/")) {
                        LocalDate d = LocalDate.parse(k.substring(k.length() - 14, k.length() - 4));
                        if (!d.isBefore(metricsFrom) && !d.isAfter(lastDay)) met.add(k);
                    }
                    for (String k : met) {
                        archive.fetch(k);
                        readMetrics(dir.resolve(k), f);
                    }
                    bySpot.put(spot, f);
                    int n = done.incrementAndGet();
                    if (n % 50 == 0) System.out.println("合约数据进度 " + n);
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    System.out.println("合约数据失败：" + e.getMessage());
                }
            }
        }
        System.out.println("合约数据：" + bySpot.size() + " 个币");
    }

    /** 用最近一次结算的费率填后面的空格，最多填 maxGap 小时（下架或停更后不再沿用）。 */
    static void fillForward(float[] a, int maxGap) {
        int gap = 0;
        for (int h = 1; h < a.length; h++) {
            if (!Float.isNaN(a[h])) {
                gap = 0;
            } else if (!Float.isNaN(a[h - 1]) && gap < maxGap) {
                a[h] = a[h - 1];
                gap++;
            }
        }
    }

    private int hourOf(long t) {
        long off = t - start;
        return off < 0 ? -1 : (int) (off / Series.H);
    }

    /** CSV：calc_time, funding_interval_hours, last_funding_rate（可能带表头）。 */
    void readFunding(Path zip, Fut f) throws IOException {
        for (String line : lines(zip)) {
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;
            String[] c = line.split(",");
            long t = Long.parseLong(c[0].trim());
            int interval = c.length > 2 ? (int) Double.parseDouble(c[1]) : 8;
            double rate = Double.parseDouble(c[c.length - 1]) * 100;
            int h = hourOf(t);
            if (h < 0 || h >= hours) continue;
            // 包按月份顺序读，第一条记录就是数据里最早的结算；比数据开始晚一周以上才算"期间内新上线"
            if (!f.seenFunding && h > 7 * 24) f.listedHour = Math.max(0, h - Math.max(1, interval));
            f.seenFunding = true;
            f.funding[h] = (float) rate;
        }
    }

    /** CSV：create_time, symbol, sum_open_interest, sum_open_interest_value, count_toptrader_long_short_ratio, sum_toptrader_long_short_ratio, count_long_short_ratio, sum_taker_long_short_vol_ratio。 */
    void readMetrics(Path zip, Fut f) throws IOException {
        for (String line : lines(zip)) {
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;
            String[] c = line.split(",");
            if (c.length < 8) continue;
            long t = LocalDateTime.parse(c[0].trim(), TS).toInstant(ZoneOffset.UTC).toEpochMilli();
            int h = hourOf(t);
            if (h < 0 || h >= hours) continue;
            f.oiValue[h] = num(c[3]);
            f.topLs[h] = num(c[5]);
            f.ls[h] = num(c[6]);
            f.takerLs[h] = num(c[7]);
        }
    }

    private static float num(String s) {
        try {
            return s.isBlank() ? Float.NaN : Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static List<String> lines(Path zip) throws IOException {
        if (!Files.exists(zip)) return List.of();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e = z.getNextEntry();
            if (e == null) return List.of();
            return new BufferedReader(new InputStreamReader(z, StandardCharsets.US_ASCII)).lines().toList();
        }
    }

    /** 数据概况：有资金费率的币、有持仓数据的币、期间内新上线的合约数。 */
    public String summary() {
        long withOi = bySpot.values().stream().filter(f -> {
            for (float v : f.oiValue) if (!Float.isNaN(v)) return true;
            return false;
        }).count();
        long listed = bySpot.values().stream().filter(f -> f.listedHour >= 0).count();
        return "合约数据：进过标的池的币里有永续合约的 " + bySpot.size() + " 个，其中有持仓/多空比数据的 " + withOi + " 个；数据期间内新上线的合约 " + listed + " 个。";
    }

    /** 测试用：直接放入一个币的数据。 */
    void put(String spot, Fut f) {
        bySpot.put(spot, f);
    }
}
