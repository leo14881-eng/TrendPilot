package com.trendpilot.research;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;

/**
 * 研究：身份、供给、解锁、价值捕获与证据缺口。
 * <ul>
 * <li>资料按天从 CoinGecko（交易所交易对 → 项目、市值与供给）和 DefiLlama（TVL、手续费、收入）批量拉取并存库，
 *     Discovery 只读本地资料，不逐币请求。</li>
 * <li>每轮研究：轮转 25 个币 + 前 10 个 EARLY + 前 10 个价格异动币，结论写入 research_record。</li>
 * <li>解锁计划取 DefiLlama 公开数据集（每天一次）：未来 30 天解锁 ≥ 流通 5% 标 HEAVY_UNLOCK_30D，
 *     7 天内单次 ≥ 1% 标 UNLOCK_CLIFF_7D，否则 UNLOCK_LIGHT；未收录的币记缺口 UNLOCK_SCHEDULE_NOT_COVERED。
 *     这些都只是标签和缺口，不当作拒绝理由。</li>
 * </ul>
 */
@Service
public class ResearchService {

    static final Duration IDENTITY_TTL = Duration.ofHours(24);
    static final Duration MARKET_TTL = Duration.ofHours(12);
    static final Duration PROTOCOL_TTL = Duration.ofHours(24);
    static final Duration UNLOCK_TTL = Duration.ofHours(24);
    /** 未来 30 天解锁 ≥ 流通量 5%：供给压力大。 */
    static final double HEAVY_UNLOCK_30D_PCT = 5;
    /** 7 天内有一次 ≥ 流通量 1% 的集中解锁。 */
    static final double NEAR_CLIFF_PCT = 1;
    static final Map<Venue, String> EXCHANGE_IDS = Map.of(Venue.BINANCE, "binance", Venue.BYBIT, "bybit_spot");

    private static final Logger log = LoggerFactory.getLogger(ResearchService.class);

    private final CoinGeckoClient coinGecko;
    private final DefiLlamaClient defiLlama;
    private final JdbcClient jdbc;
    private final ResearchProperties props;
    private int rotationCursor;

    public ResearchService(CoinGeckoClient coinGecko, DefiLlamaClient defiLlama, JdbcClient jdbc,
                           ResearchProperties props) {
        this.coinGecko = coinGecko;
        this.defiLlama = defiLlama;
        this.jdbc = jdbc;
        this.props = props;
    }

    // ---------- 资料刷新（后台任务调用，失败只记日志，旧资料保留但会过期） ----------

    /**
     * 刷新到期的资料。每项失败后进入退避（2、4、8…小时，封顶 24 小时），期间不再请求；
     * CoinGecko 限流时本轮剩余的 CoinGecko 项全部跳过。失败只影响研究缺口，不阻塞 Discovery。
     *
     * @return 本次刷新了哪些
     */
    public List<String> refreshDue(Instant now) {
        List<String> done = new ArrayList<>();
        if (!props.enabled()) return done;
        boolean[] limited = {false};
        for (Venue v : Venue.values()) {
            if (stale("SELECT MAX(updated_at) FROM coin_identity WHERE venue = '" + v + "'", IDENTITY_TTL, now)) {
                attempt("IDENTITY_" + v, true, limited, now, done, () -> refreshIdentity(v, now));
            }
        }
        if (stale("SELECT MAX(updated_at) FROM coin_market", MARKET_TTL, now)) {
            List<String> ids = jdbc.sql("SELECT DISTINCT coin_id FROM coin_identity").query(String.class).list();
            if (!ids.isEmpty()) attempt("MARKETS", true, limited, now, done, () -> saveMarkets(coinGecko.markets(ids), now));
        }
        if (stale("SELECT MAX(updated_at) FROM coin_category", IDENTITY_TTL, now)) {
            for (String category : props.excludedCategories()) {
                attempt("CATEGORY_" + category, true, limited, now, done,
                        () -> saveCategory(category, coinGecko.categoryCoinIds(category), now));
            }
        }
        if (stale("SELECT MAX(updated_at) FROM protocol_value", PROTOCOL_TTL, now)) {
            attempt("DEFILLAMA", false, limited, now, done, () -> saveProtocols(defiLlama.protocolsByGeckoId(), now));
        }
        if (stale("SELECT MAX(updated_at) FROM unlock_schedule", UNLOCK_TTL, now)) {
            attempt("UNLOCKS", false, limited, now, done, () -> saveUnlocks(defiLlama.unlocksByGeckoId(now), now));
        }
        return done;
    }

    private void attempt(String item, boolean coinGeckoItem, boolean[] limited, Instant now, List<String> done,
                         Runnable work) {
        Backoff b = jdbc.sql("SELECT failures, retry_at FROM research_backoff WHERE item = :i").param("i", item)
                .query((rs, n) -> new Backoff(rs.getInt(1), Instant.parse(rs.getString(2)))).optional().orElse(null);
        if (b != null && now.isBefore(b.retryAt())) return;
        if (coinGeckoItem && limited[0]) return;
        try {
            work.run();
            jdbc.sql("DELETE FROM research_backoff WHERE item = :i").param("i", item).update();
            done.add(item);
        } catch (RuntimeException e) {
            if (e instanceof CoinGeckoClient.RateLimited) limited[0] = true;
            int failures = b == null ? 1 : b.failures() + 1;
            Duration wait = Duration.ofHours(Math.min(24, 1L << Math.min(failures, 5)));
            jdbc.sql("INSERT OR REPLACE INTO research_backoff(item, failures, retry_at) VALUES (:i, :f, :r)")
                    .param("i", item).param("f", failures).param("r", now.plus(wait).toString()).update();
            log.warn("研究资料 {} 刷新失败（连续第 {} 次，{} 小时后再试，期间记为研究缺口）：{}", item, failures,
                    wait.toHours(), e.getMessage());
        }
    }

    /** 某项资料连续失败次数与下次可重试时间。 */
    record Backoff(int failures, Instant retryAt) {
    }

    /** 当前处在失败退避中的资料项（供状态接口展示）。 */
    public Map<String, Instant> backoffs() {
        Map<String, Instant> out = new LinkedHashMap<>();
        jdbc.sql("SELECT item, retry_at FROM research_backoff ORDER BY item")
                .query((rs, n) -> out.put(rs.getString(1), Instant.parse(rs.getString(2)))).list();
        return out;
    }

    private boolean stale(String sql, Duration ttl, Instant now) {
        Optional<String> last = jdbc.sql(sql).query(String.class).optional();
        return last.isEmpty() || Duration.between(Instant.parse(last.get()), now).compareTo(ttl) > 0;
    }

    @Transactional
    public void saveIdentity(Venue venue, List<CoinGeckoClient.ExchangeTicker> tickers, Instant now) {
        jdbc.sql("DELETE FROM coin_identity WHERE venue = :v").param("v", venue.name()).update();
        for (var t : tickers) {
            if (!"USDT".equalsIgnoreCase(t.target()) || t.coinId() == null || t.coinId().isBlank()) continue;
            jdbc.sql("INSERT OR IGNORE INTO coin_identity(venue, symbol, coin_id, updated_at) VALUES (:v, :s, :c, :t)")
                    .param("v", venue.name()).param("s", t.base().toUpperCase(java.util.Locale.ROOT) + "USDT").param("c", t.coinId())
                    .param("t", now.toString()).update();
        }
    }

    private void refreshIdentity(Venue venue, Instant now) {
        List<CoinGeckoClient.ExchangeTicker> tickers = coinGecko.exchangeTickers(EXCHANGE_IDS.get(venue));
        if (tickers.isEmpty()) throw new ResearchDataException("CoinGecko 没有返回 " + venue + " 交易对");
        saveIdentity(venue, tickers, now);
    }

    /**
     * 替换某个分类的币列表。新列表比已存的少一半以上（分页没取全、接口异常）时拒绝替换并保留旧的，
     * 否则稳定币、代币化股票会突然不再被排除。
     */
    @Transactional
    public void saveCategory(String category, List<String> coinIds, Instant now) {
        int stored = jdbc.sql("SELECT COUNT(*) FROM coin_category WHERE category = :c").param("c", category)
                .query(Integer.class).single();
        if (stored >= 10 && coinIds.size() < stored * 0.5) {
            throw new ResearchDataException("CoinGecko 分类 " + category + " 从 " + stored + " 个骤降到 " + coinIds.size()
                    + " 个，视为异常数据，保留旧列表");
        }
        jdbc.sql("DELETE FROM coin_category WHERE category = :c").param("c", category).update();
        for (String id : coinIds) {
            jdbc.sql("INSERT OR IGNORE INTO coin_category(coin_id, category, updated_at) VALUES (:id, :c, :t)")
                    .param("id", id).param("c", category).param("t", now.toString()).update();
        }
    }

    /** 按 CoinGecko 分类应排除的交易对（例如稳定币、代币化股票）；资料没加载时为空，由配置里的名单兜底。 */
    public Set<String> excludedSymbols(Venue venue) {
        return jdbc.sql("""
                SELECT DISTINCT i.symbol FROM coin_identity i JOIN coin_category c ON c.coin_id = i.coin_id
                WHERE i.venue = :v
                """).param("v", venue.name()).query(String.class).list().stream().collect(Collectors.toSet());
    }

    @Transactional
    public void saveMarkets(List<CoinGeckoClient.Market> markets, Instant now) {
        for (var m : markets) {
            jdbc.sql("""
                    INSERT OR REPLACE INTO coin_market(coin_id, symbol, market_cap, fdv, circulating, total, max_supply, updated_at)
                    VALUES (:id, :s, :mc, :fdv, :c, :t, :m, :u)
                    """)
                    .param("id", m.coinId()).param("s", m.symbol()).param("mc", nan(m.marketCap()))
                    .param("fdv", nan(m.fdv())).param("c", nan(m.circulating())).param("t", nan(m.total()))
                    .param("m", nan(m.max())).param("u", now.toString()).update();
        }
    }

    @Transactional
    public void saveProtocols(Map<String, DefiLlamaClient.Protocol> protocols, Instant now) {
        jdbc.sql("DELETE FROM protocol_value").update();
        for (var p : protocols.values()) {
            jdbc.sql("""
                    INSERT INTO protocol_value(gecko_id, name, category, tvl, fees_24h, revenue_24h, updated_at)
                    VALUES (:g, :n, :c, :tvl, :f, :r, :u)
                    """)
                    .param("g", p.geckoId()).param("n", p.name()).param("c", p.category()).param("tvl", nan(p.tvl()))
                    .param("f", nan(p.fees24h())).param("r", nan(p.revenue24h())).param("u", now.toString()).update();
        }
    }

    @Transactional
    public void saveUnlocks(Map<String, DefiLlamaClient.Unlock> unlocks, Instant now) {
        if (unlocks.isEmpty()) throw new ResearchDataException("DefiLlama 解锁数据集为空");
        jdbc.sql("DELETE FROM unlock_schedule").update();
        for (var u : unlocks.values()) {
            jdbc.sql("""
                    INSERT INTO unlock_schedule(gecko_id, next_unlock_at, next_unlock_pct, unlock_30d_pct, circulating, max_supply, updated_at)
                    VALUES (:g, :na, :np, :u30, :c, :m, :t)
                    """)
                    .param("g", u.geckoId()).param("na", u.nextUnlockAt() == null ? null : u.nextUnlockAt().toString())
                    .param("np", u.nextUnlockPct()).param("u30", u.unlock30dPct()).param("c", u.circulating())
                    .param("m", nan(u.maxSupply())).param("t", now.toString()).update();
        }
    }

    // ---------- 研究结论（只读本地资料） ----------

    /** 一个标的的研究结论；身份资料缺失或过期时返回 DATA_UNAVAILABLE。 */
    public ResearchRecord research(Venue venue, String symbol, Ticker24h ticker, Instant now) {
        if (stale("SELECT MAX(updated_at) FROM coin_identity WHERE venue = '" + venue + "'", IDENTITY_TTL.multipliedBy(3), now)) {
            return ResearchRecord.unavailable(venue, symbol);
        }
        List<String> ids = coinIds(venue, symbol);
        String alias = null;
        // 交易所和 CoinGecko 对倍数前缀的写法可能不同（1000SATS / SATS、1MBABYDOGE / BABYDOGE）：精确匹配不到时才试别名，
        // 别名也只认唯一结果，匹配到多个仍按身份歧义处理
        if (ids.isEmpty()) {
            for (String a : aliases(symbol)) {
                List<String> found = coinIds(venue, a);
                if (!found.isEmpty()) {
                    ids = found;
                    alias = a;
                    break;
                }
            }
        }
        Identity identity = ids.isEmpty() ? Identity.UNVERIFIED : ids.size() > 1 ? Identity.AMBIGUOUS : Identity.VERIFIED;
        String coinId = ids.size() == 1 ? ids.getFirst() : null;

        List<String> labels = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        if (alias != null) labels.add("IDENTITY_ALIAS_" + alias);
        if (ticker != null) {
            double ch = ticker.priceChangePct();
            if (ch >= 20) labels.add("MOVE_24H_GE_20");
            else if (ch >= 8) labels.add("MOVE_24H_GE_8");
        }
        double mc = Double.NaN, fdv = Double.NaN, circRatio = Double.NaN;
        double tvl = Double.NaN, fees = Double.NaN, revenue = Double.NaN;
        if (identity == Identity.UNVERIFIED) gaps.add("IDENTITY_NOT_ON_COINGECKO");
        if (coinId != null) {
            var market = jdbc.sql("SELECT market_cap, fdv, circulating, total, max_supply FROM coin_market WHERE coin_id = :id")
                    .param("id", coinId).query((rs, i) -> new double[]{d(rs.getObject(1)), d(rs.getObject(2)),
                            d(rs.getObject(3)), d(rs.getObject(4)), d(rs.getObject(5))}).optional();
            if (market.isEmpty()) {
                gaps.add("SUPPLY_DATA_MISSING");
            } else {
                double[] m = market.get();
                mc = m[0];
                fdv = m[1];
                double supply = !Double.isNaN(m[4]) && m[4] > 0 ? m[4] : m[3];
                if (Double.isNaN(m[2]) || Double.isNaN(supply) || supply <= 0) {
                    gaps.add("SUPPLY_DATA_MISSING");
                } else {
                    circRatio = m[2] / supply;
                    if (circRatio < props.lowFloatRatio()) labels.add("LOW_FLOAT");
                }
            }
            var protocol = jdbc.sql("SELECT tvl, fees_24h, revenue_24h FROM protocol_value WHERE gecko_id = :id")
                    .param("id", coinId).query((rs, i) -> new double[]{d(rs.getObject(1)), d(rs.getObject(2)),
                            d(rs.getObject(3))}).optional();
            if (protocol.isPresent()) {
                tvl = protocol.get()[0];
                fees = protocol.get()[1];
                revenue = protocol.get()[2];
                if (!Double.isNaN(revenue) && revenue > 0) labels.add("HAS_REVENUE");
                else gaps.add("VALUE_CAPTURE_UNCLEAR");
            } else {
                gaps.add("VALUE_CAPTURE_NOT_COVERED");
            }
        }
        if (coinId != null && !stale("SELECT MAX(updated_at) FROM unlock_schedule", UNLOCK_TTL.multipliedBy(3), now)) {
            var unlock = jdbc.sql("SELECT next_unlock_at, next_unlock_pct, unlock_30d_pct FROM unlock_schedule WHERE gecko_id = :id")
                    .param("id", coinId).query((rs, i) -> new Object[]{rs.getString(1), rs.getDouble(2), rs.getDouble(3)})
                    .optional();
            if (unlock.isPresent()) {
                Object[] u = unlock.get();
                if ((double) u[2] >= HEAVY_UNLOCK_30D_PCT) labels.add("HEAVY_UNLOCK_30D");
                if (u[0] != null && Instant.parse((String) u[0]).isBefore(now.plus(Duration.ofDays(7)))
                        && (double) u[1] >= NEAR_CLIFF_PCT) labels.add("UNLOCK_CLIFF_7D");
                if (labels.stream().noneMatch(l -> l.startsWith("HEAVY_UNLOCK") || l.startsWith("UNLOCK_CLIFF"))) {
                    labels.add("UNLOCK_LIGHT");
                }
            } else {
                gaps.add("UNLOCK_SCHEDULE_NOT_COVERED"); // DefiLlama 没收录该币的解锁计划
            }
        } else {
            gaps.add("UNLOCK_SCHEDULE_NOT_COVERED");
        }
        return new ResearchRecord(venue, symbol, coinId, identity, mc, fdv, circRatio, tvl, fees, revenue,
                List.copyOf(labels), List.copyOf(gaps));
    }

    /** 本轮要研究的标的：轮转 + 前 N 个 EARLY + 前 N 个价格异动。 */
    public record Target(Venue venue, Ticker24h ticker, String source) {
    }

    public synchronized List<Target> selectTargets(List<Target> universe, List<Target> earlyRanked) {
        Map<String, Target> out = new LinkedHashMap<>();
        earlyRanked.stream().limit(props.topEarly()).forEach(t -> out.putIfAbsent(t.ticker().symbol(), t));
        universe.stream().sorted(Comparator.comparingDouble((Target t) -> -Math.abs(t.ticker().priceChangePct())))
                .limit(props.topMovers())
                .forEach(t -> out.putIfAbsent(t.ticker().symbol(), new Target(t.venue(), t.ticker(), "MOVER")));
        List<Target> sorted = universe.stream().sorted(Comparator.comparing(t -> t.ticker().symbol())).toList();
        for (int i = 0; i < Math.min(props.rotation(), sorted.size()); i++) {
            Target t = sorted.get((rotationCursor + i) % sorted.size());
            out.putIfAbsent(t.ticker().symbol(), new Target(t.venue(), t.ticker(), "ROTATION"));
        }
        if (!sorted.isEmpty()) rotationCursor = (rotationCursor + props.rotation()) % sorted.size();
        return List.copyOf(out.values());
    }

    /** 研究一批标的并记录结论。@return 按标的索引的结论 */
    public Map<String, ResearchRecord> researchAndRecord(String generation, List<Target> targets, Instant now) {
        Map<String, ResearchRecord> out = new HashMap<>();
        for (Target t : targets) {
            ResearchRecord r = research(t.venue(), t.ticker().symbol(), t.ticker(), now);
            out.put(t.ticker().symbol(), r);
            jdbc.sql("""
                    INSERT INTO research_record(generation, venue, symbol, source, coin_id, identity, market_cap, fdv,
                        circ_ratio, tvl, fees_24h, revenue_24h, labels, gaps, verdict, capital_ready, created_at)
                    VALUES (:g, :v, :s, :src, :c, :i, :mc, :fdv, :cr, :tvl, :f, :r, :l, :gaps, :verdict, 0, :t)
                    """)
                    .param("g", generation).param("v", t.venue().name()).param("s", t.ticker().symbol())
                    .param("src", t.source()).param("c", r.coinId()).param("i", r.identity().name())
                    .param("mc", nan(r.marketCap())).param("fdv", nan(r.fdv())).param("cr", nan(r.circulatingRatio()))
                    .param("tvl", nan(r.tvl())).param("f", nan(r.fees24h())).param("r", nan(r.revenue24h()))
                    .param("l", String.join(",", r.labels())).param("gaps", String.join(",", r.gaps()))
                    .param("verdict", r.verdict()).param("t", now.toString()).update();
        }
        return out;
    }

    private List<String> coinIds(Venue venue, String symbol) {
        return jdbc.sql("SELECT coin_id FROM coin_identity WHERE venue = :v AND symbol = :s")
                .param("v", venue.name()).param("s", symbol).query(String.class).list();
    }

    /** 倍数前缀（交易所为了价格好看把 1000 个币打包成一个单位）。长的在前。 */
    static final List<String> MULTIPLIER_PREFIXES = List.of("1000000", "10000", "1000", "100", "1M");

    /** 同一个币的其他写法：去掉倍数前缀，或者加上常见倍数前缀。 */
    static List<String> aliases(String symbol) {
        if (!symbol.endsWith("USDT")) return List.of();
        String base = symbol.substring(0, symbol.length() - 4);
        List<String> out = new ArrayList<>();
        for (String p : MULTIPLIER_PREFIXES) {
            if (base.startsWith(p) && base.length() > p.length() && Character.isLetter(base.charAt(p.length()))) {
                out.add(base.substring(p.length()) + "USDT");
                return out;
            }
        }
        for (String p : MULTIPLIER_PREFIXES) out.add(p + base + "USDT");
        return out;
    }

    /**
     * other 交易所上与 primary 交易所某个已上市交易对是同一个币（CoinGecko coin id 相同）的交易对，
     * 例如 Binance 的 BEAMXUSDT 与 Bybit 的 BEAMUSDT。名字不同也算重复，避免同一个币在两个池里各买一次。
     */
    public Set<String> sameCoinOn(Venue other, Venue primary, Set<String> primaryListed) {
        Set<String> out = new java.util.HashSet<>();
        jdbc.sql("""
                SELECT o.symbol, p.symbol FROM coin_identity o JOIN coin_identity p ON p.coin_id = o.coin_id
                WHERE o.venue = :o AND p.venue = :p
                """).param("o", other.name()).param("p", primary.name())
                .query((rs, i) -> {
                    if (primaryListed.contains(rs.getString(2))) out.add(rs.getString(1));
                    return null;
                }).list();
        return out;
    }

    public Set<String> symbolsWithIdentity(Venue venue) {
        return jdbc.sql("SELECT DISTINCT symbol FROM coin_identity WHERE venue = :v").param("v", venue.name())
                .query(String.class).list().stream().collect(Collectors.toSet());
    }

    private static Double nan(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    private static double d(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }
}
