package com.trendpilot.backtest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import com.trendpilot.signal.EarlySignal;

/**
 * 买入端研究（EntryStudy，2026-10-23）：开仓那一刻的特征，只用当时已收盘的数据。
 * 信号类特征（评分、命中项）随机对照组没有，记 NaN / 空。
 */
record EntryFeat(String symbol, int q, double score, String hits, double rel1h, double rel4h, double accel,
                 double volumeAccel, double compression, double ret15m, double ret1h, double ret4h, double ret24h,
                 double fromHigh24h, double fromHigh7d, double quoteVol24h, int volRank, String riskRegime, int regime6,
                 double trendShare, double breadth24h, double above20d, double btc1h, double btc24h, double alt24h,
                 int utcHour, int weekday, double ageDays, double atr1d) {

    static final String HEADER = "score,hits,rel1h,rel4h,accel,volAccel,compression,ret15m,ret1h,ret4h,ret24h,"
            + "fromHigh24h,fromHigh7d,quoteVol24h,volRank,riskRegime,regime6,trendShare,breadth24h,above20d,btc1h,btc24h,alt24h,"
            + "utcHour,weekday,ageDays,atr1d";

    String csv() {
        return String.format(Locale.ROOT, "%.2f,%s,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.0f,%d,%s,%d,%.3f,%.3f,%.3f,%.4f,%.4f,%.4f,%d,%d,%.1f,%.4f",
                score, hits, rel1h, rel4h, accel, volumeAccel, compression, ret15m, ret1h, ret4h, ret24h, fromHigh24h, fromHigh7d,
                quoteVol24h, volRank, riskRegime, regime6, trendShare, breadth24h, above20d, btc1h, btc24h, alt24h, utcHour, weekday,
                ageDays, atr1d);
    }

    static EntryFeat of(MarketData data, Regimes regimes, String sym, int q, EarlySignal s, double quoteVol, String riskRegime,
                        double atr1d) {
        Series x = data.get(sym);
        double c = x.close[q];
        List<String> u = data.universe(q);
        int rank = u.indexOf(sym);
        MarketData.Context ctx = (q + 1) / 4 - 1 >= 0 ? data.contextAtHour((q + 1) / 4 - 1) : null;
        var t = Instant.ofEpochMilli(data.time(q) + Series.Q).atZone(ZoneOffset.UTC);
        int first = x.firstIndex();
        return new EntryFeat(sym, q,
                s == null ? Double.NaN : s.score(),
                s == null ? "" : String.join("+", s.hits()),
                s == null ? Double.NaN : s.rel1h(), s == null ? Double.NaN : s.rel4h(),
                s == null ? Double.NaN : s.accel(), s == null ? Double.NaN : s.volumeAccel(),
                s == null ? Double.NaN : s.compression(), s == null ? Double.NaN : s.ret15m(),
                ret(x, q, 4), ret(x, q, 16), ret(x, q, 96),
                fromHigh(x, q, 96, c), fromHigh(x, q, 7 * 96, c), quoteVol, rank,
                riskRegime, regimes.at(q),
                ctx == null ? Double.NaN : ctx.trendShare(), ctx == null ? Double.NaN : ctx.breadth(),
                above20d(data, u, q),
                ctx == null ? Double.NaN : ctx.btc1h(), ctx == null ? Double.NaN : ctx.btc24h(),
                ctx == null ? Double.NaN : ctx.alt24h(),
                t.getHour(), t.getDayOfWeek().getValue(),
                first < 0 ? Double.NaN : (q - first) / (double) MarketData.DAY, atr1d);
    }

    private static double ret(Series x, int q, int n) {
        if (q - n < 0) return Double.NaN;
        double a = x.close[q - n];
        return Double.isNaN(a) || a <= 0 ? Double.NaN : x.close[q] / a - 1;
    }

    private static double fromHigh(Series x, int q, int n, double c) {
        double h = Double.NaN;
        for (int i = Math.max(0, q - n + 1); i <= q; i++) {
            if (!Float.isNaN(x.high[i]) && (Double.isNaN(h) || x.high[i] > h)) h = x.high[i];
        }
        return Double.isNaN(h) || h <= 0 ? Double.NaN : c / h - 1;
    }

    /** 币池里收盘价在 20 日均线（按每天最后一根 15m 收盘）上方的比例。 */
    static double above20d(MarketData data, List<String> universe, int q) {
        int above = 0, n = 0;
        for (String sym : universe) {
            Series x = data.get(sym);
            if (x == null || !x.has(q)) continue;
            double sum = 0;
            int k = 0;
            for (int d = 0; d < 20; d++) {
                int i = q - d * MarketData.DAY;
                if (i < 0 || Float.isNaN(x.close[i])) break;
                sum += x.close[i];
                k++;
            }
            if (k < 20) continue;
            n++;
            if (x.close[q] > sum / 20) above++;
        }
        return n == 0 ? Double.NaN : above / (double) n;
    }

    /** 按名字取数值特征（过滤表达式用）。 */
    double num(String name) {
        return switch (name) {
            case "score" -> score;
            case "rel1h" -> rel1h;
            case "rel4h" -> rel4h;
            case "accel" -> accel;
            case "volAccel" -> volumeAccel;
            case "compression" -> compression;
            case "ret15m" -> ret15m;
            case "ret1h" -> ret1h;
            case "ret4h" -> ret4h;
            case "ret24h" -> ret24h;
            case "fromHigh24h" -> fromHigh24h;
            case "fromHigh7d" -> fromHigh7d;
            case "quoteVol24h" -> quoteVol24h;
            case "volRank" -> volRank;
            case "regime6" -> regime6;
            case "trendShare" -> trendShare;
            case "breadth24h" -> breadth24h;
            case "above20d" -> above20d;
            case "btc1h" -> btc1h;
            case "btc24h" -> btc24h;
            case "alt24h" -> alt24h;
            case "utcHour" -> utcHour;
            case "weekday" -> weekday;
            case "ageDays" -> ageDays;
            case "atr1d" -> atr1d;
            default -> throw new IllegalArgumentException("未知特征 " + name);
        };
    }

    /**
     * 过滤表达式：conj ('|' conj)*，conj = term ('&' term)*；term = 特征 op 数值（op: &gt;= &lt;= &gt; &lt; = !=），
     * 或 hits~X / hits!~X（命中项包含 / 不包含），riskRegime=X / riskRegime!=X。整体前缀 "NOT:" 取反（剔除条件）。
     * 数值为 NaN 时 term 不成立。
     */
    static java.util.function.Predicate<EntryFeat> parse(String expr) {
        boolean not = expr.startsWith("NOT:");
        String body = not ? expr.substring(4) : expr;
        List<List<java.util.function.Predicate<EntryFeat>>> dnf = new java.util.ArrayList<>();
        for (String conj : body.split("\\|")) {
            List<java.util.function.Predicate<EntryFeat>> terms = new java.util.ArrayList<>();
            for (String t : conj.split("&")) terms.add(term(t.trim()));
            dnf.add(terms);
        }
        java.util.function.Predicate<EntryFeat> p = f -> dnf.stream().anyMatch(c -> c.stream().allMatch(t -> t.test(f)));
        return not ? p.negate() : p;
    }

    private static java.util.function.Predicate<EntryFeat> term(String t) {
        if (t.startsWith("hits!~")) {
            String x = t.substring(6);
            return f -> !List.of(f.hits().split("\\+")).contains(x);
        }
        if (t.startsWith("hits~")) {
            String x = t.substring(5);
            return f -> List.of(f.hits().split("\\+")).contains(x);
        }
        if (t.startsWith("riskRegime!=")) {
            String x = t.substring(12);
            return f -> !f.riskRegime().equals(x);
        }
        if (t.startsWith("riskRegime=")) {
            String x = t.substring(11);
            return f -> f.riskRegime().equals(x);
        }
        for (String op : new String[]{">=", "<=", "!=", ">", "<", "="}) {
            int i = t.indexOf(op);
            if (i <= 0) continue;
            String name = t.substring(0, i);
            double v = Double.parseDouble(t.substring(i + op.length()));
            return f -> {
                double x = f.num(name);
                if (Double.isNaN(x)) return false;
                return switch (op) {
                    case ">=" -> x >= v;
                    case "<=" -> x <= v;
                    case "!=" -> x != v;
                    case ">" -> x > v;
                    case "<" -> x < v;
                    default -> x == v;
                };
            };
        }
        throw new IllegalArgumentException("看不懂的过滤条件 " + t);
    }
}
