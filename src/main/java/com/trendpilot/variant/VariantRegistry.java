package com.trendpilot.variant;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.portfolio.Ledger;
import com.trendpilot.portfolio.StrongVariantBook;
import com.trendpilot.portfolio.VariantBook;

/**
 * 配置化影子变体的登记表（variant_registry）：启动时把 application.yml 的 trendpilot.variants.list 同步进来，
 * 每周读取的回测候选也登记在这里。每个变体一本独立账（V5 继续用原来的 v5_position，数据不动）。
 * <ul>
 * <li>base 只能是 V2：变体永远不会改 BTC 每日短线、BTC 均线趋势、V1、V3。</li>
 * <li>同时最多 {@link OptimizeProperties#maxActive()} 个 ACTIVE（开新仓）的变体；满了的新变体不登记，只记日志。</li>
 * <li>状态：ACTIVE 正常买卖 → STOPPED 停开新仓、持仓按规则卖完 → ARCHIVED；PROMOTED = 已转正（同样停开新仓，卖完归档）。</li>
 * </ul>
 */
@Component
public class VariantRegistry {

    private static final Logger log = LoggerFactory.getLogger(VariantRegistry.class);
    /** 已被其他账本占用的名字。 */
    static final Set<String> RESERVED = Set.of("V1", "V2", "V2D", "V2D15", "V3", "V4", "BTC");
    static final String NAME_RULE = "^V[0-9]{1,3}[A-Z0-9]{0,6}$";
    public static final String ACTIVE = "ACTIVE", STOPPED = "STOPPED", ARCHIVED = "ARCHIVED", PROMOTED = "PROMOTED";
    /** V5 上线时间（2026-10-23 06:15 UTC），数据库里没有 V5 买入记录时用它作为同期对比起点。 */
    static final Instant V5_START = Instant.parse("2026-10-23T06:15:00Z");

    /** 一个变体。overrides 含每笔金额（trade-usdt）。 */
    public record Variant(String name, String base, VariantOverrides overrides, String table, String source,
                          boolean backtestPassed, String evidence, String status, Instant startedAt, Ledger book) {

        public boolean opensNew() {
            return ACTIVE.equals(status);
        }
    }

    private final JdbcClient jdbc;
    private final StrategyParams params;
    private final VariantsProperties config;
    private final OptimizeProperties opt;
    private final StrongVariantBook v5;
    private final Clock clock;
    private final Map<String, Ledger> books = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean synced;

    @org.springframework.beans.factory.annotation.Autowired
    public VariantRegistry(JdbcClient jdbc, StrategyParams params, VariantsProperties config, OptimizeProperties opt,
                           StrongVariantBook v5) {
        this(jdbc, params, config, opt, v5, Clock.systemUTC());
    }

    VariantRegistry(JdbcClient jdbc, StrategyParams params, VariantsProperties config, OptimizeProperties opt,
                    StrongVariantBook v5, Clock clock) {
        this.jdbc = jdbc;
        this.params = params;
        this.config = config;
        this.opt = opt;
        this.v5 = v5;
        this.clock = clock;
    }

    public static String tableFor(String name) {
        return StrongVariantBook.BOOK.equals(name) ? "v5_position" : name.toLowerCase(Locale.ROOT) + "_position";
    }

    static void validateName(String name) {
        if (name == null || !name.matches(NAME_RULE) || RESERVED.contains(name)) {
            throw new IllegalArgumentException("变体名不合法或已被占用：" + name + "（要求 V 加数字，例如 V6；不能用 V1/V2/V2D15/V3）");
        }
    }

    /** 配置里的一个变体换算成参数改动（trade-usdt 合并进去）。 */
    static VariantOverrides overridesOf(VariantsProperties.Def d) {
        if (d.base() != null && !"V2".equalsIgnoreCase(d.base())) {
            throw new IllegalArgumentException("变体 " + d.name() + " 的 base 只能是 V2（不改 BTC 每日短线、BTC 均线趋势、V1、V3）");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (d.overrides() != null) m.putAll(d.overrides());
        if (d.tradeUsdt() != null) m.put("trade-usdt", d.tradeUsdt());
        return VariantOverrides.parse(m);
    }

    /** 启动后第一次用到时把配置同步进来（之后每次读都直接用数据库）。出错的单个变体只记日志，不影响其他变体和 V2。 */
    public synchronized void sync() {
        if (synced) return;
        Instant now = clock.instant();
        for (VariantsProperties.Def d : config.list()) {
            try {
                validateName(d.name());
                VariantOverrides o = overridesOf(d);
                String json = o.inline();
                Optional<String[]> row = jdbc.sql("SELECT overrides_json, status FROM variant_registry WHERE name = :n")
                        .param("n", d.name()).query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)}).optional();
                String table = tableFor(d.name());
                VariantBook.ensureTable(jdbc, table);
                boolean passed = Boolean.TRUE.equals(d.backtestPassed());
                if (row.isEmpty()) {
                    if (activeCount() >= opt.maxActive()) {
                        log.warn("变体 {} 没有上线：已有 {} 个活跃变体（最多 {} 个）", d.name(), activeCount(), opt.maxActive());
                        continue;
                    }
                    Instant start = StrongVariantBook.BOOK.equals(d.name()) ? firstBuy(d.name()).orElse(V5_START) : now;
                    jdbc.sql("""
                            INSERT INTO variant_registry(name, base, position_table, overrides_json, source, backtest_passed, evidence,
                                status, started_at, status_at, note)
                            VALUES (:n, 'V2', :t, :o, 'CONFIG', :p, :e, 'ACTIVE', :s, :now, :note)
                            """).param("n", d.name()).param("t", table).param("o", json).param("p", passed ? 1 : 0)
                            .param("e", d.evidence()).param("s", start.toString()).param("now", now.toString())
                            .param("note", o.describe()).update();
                    log.info("变体 {} 已登记：{}", d.name(), o.describe());
                } else {
                    if (!row.get()[0].equals(json)) {
                        // 参数改了：同期对比从现在重新算（之前的成绩不能算到新参数头上）
                        jdbc.sql("UPDATE variant_registry SET overrides_json = :o, started_at = :now, note = :note WHERE name = :n")
                                .param("o", json).param("now", now.toString()).param("note", "参数改动后重新计时：" + o.describe())
                                .param("n", d.name()).update();
                        log.warn("变体 {} 参数改动，同期对比从 {} 重新计算：{}", d.name(), now, o.describe());
                    }
                    jdbc.sql("UPDATE variant_registry SET backtest_passed = :p, evidence = :e WHERE name = :n AND source = 'CONFIG'")
                            .param("p", passed ? 1 : 0).param("e", d.evidence()).param("n", d.name()).update();
                }
            } catch (RuntimeException e) {
                log.warn("变体配置 {} 有问题，跳过：{}", d.name(), e.getMessage());
            }
        }
        synced = true;
    }

    private Optional<Instant> firstBuy(String book) {
        return jdbc.sql("SELECT MIN(created_at) FROM shadow_event WHERE book = :b AND type = 'BUY'").param("b", book)
                .query(String.class).optional().filter(s -> s != null && !s.isBlank()).map(Instant::parse);
    }

    public int activeCount() {
        return jdbc.sql("SELECT COUNT(*) FROM variant_registry WHERE status = 'ACTIVE'").query(Integer.class).single();
    }

    /** 没归档的全部变体（报表用），按名字排序（V5 在前）。 */
    public List<Variant> all() {
        sync();
        return jdbc.sql("SELECT * FROM variant_registry WHERE status != 'ARCHIVED' ORDER BY LENGTH(name), name")
                .query((rs, i) -> map(rs)).list().stream().filter(java.util.Objects::nonNull).toList();
    }

    /** 包括已归档的（周报、评估用）。 */
    public List<Variant> everything() {
        sync();
        return jdbc.sql("SELECT * FROM variant_registry ORDER BY LENGTH(name), name").query((rs, i) -> map(rs)).list()
                .stream().filter(java.util.Objects::nonNull).toList();
    }

    /** 要管理持仓的变体（ACTIVE / STOPPED / PROMOTED，没归档的）。 */
    public List<Variant> managed() {
        return all();
    }

    /** 正在开新仓的变体。 */
    public List<Variant> active() {
        return all().stream().filter(Variant::opensNew).toList();
    }

    public Optional<Variant> get(String name) {
        sync();
        return jdbc.sql("SELECT * FROM variant_registry WHERE name = :n").param("n", name).query((rs, i) -> map(rs)).optional();
    }

    private Variant map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String name = rs.getString("name");
        VariantOverrides o;
        try {
            o = VariantOverrides.parseInline(rs.getString("overrides_json"));
        } catch (RuntimeException e) {
            log.warn("变体 {} 的参数读不懂，跳过：{}", name, e.getMessage());
            return null;
        }
        String table = rs.getString("position_table");
        Ledger book = books.computeIfAbsent(name, n -> StrongVariantBook.BOOK.equals(n) ? v5 : new VariantBook(jdbc, params, n, table));
        return new Variant(name, rs.getString("base"), o, table, rs.getString("source"), rs.getInt("backtest_passed") == 1,
                rs.getString("evidence"), rs.getString("status"), Instant.parse(rs.getString("started_at")), book);
    }

    /**
     * 登记一个新变体（每周回测候选）。名字自动取下一个没用过的 V 数字。已有同样参数（任何状态）或活跃变体已满时不登记。
     *
     * @return 登记成功的变体名
     */
    public synchronized Optional<String> register(VariantOverrides o, String source, boolean backtestPassed, String evidence,
                                                  String note, Instant now) {
        sync();
        String json = o.inline();
        if (o.isEmpty()) return Optional.empty();
        if (jdbc.sql("SELECT COUNT(*) FROM variant_registry WHERE overrides_json = :o").param("o", json).query(Integer.class).single() > 0) {
            return Optional.empty();
        }
        if (activeCount() >= opt.maxActive()) {
            log.info("回测候选没有上线：已有 {} 个活跃变体（最多 {} 个）：{}", activeCount(), opt.maxActive(), o.describe());
            return Optional.empty();
        }
        String name = nextName();
        String table = tableFor(name);
        VariantBook.ensureTable(jdbc, table);
        jdbc.sql("""
                INSERT INTO variant_registry(name, base, position_table, overrides_json, source, backtest_passed, evidence,
                    status, started_at, status_at, note)
                VALUES (:n, 'V2', :t, :o, :src, :p, :e, 'ACTIVE', :now, :now, :note)
                """).param("n", name).param("t", table).param("o", json).param("src", source)
                .param("p", backtestPassed ? 1 : 0).param("e", evidence).param("now", now.toString())
                .param("note", note == null ? o.describe() : note).update();
        log.info("变体 {} 已上线（{}）：{}", name, source, o.describe());
        return Optional.of(name);
    }

    String nextName() {
        Set<String> used = new java.util.HashSet<>(jdbc.sql("SELECT name FROM variant_registry").query(String.class).list());
        used.addAll(config.list().stream().map(VariantsProperties.Def::name).toList());
        for (int i = 6; ; i++) {
            String n = "V" + i;
            if (!used.contains(n) && !RESERVED.contains(n)) return n;
        }
    }

    public void setStatus(String name, String status, String note, Instant now) {
        jdbc.sql("UPDATE variant_registry SET status = :st, status_at = :now, note = :note WHERE name = :n")
                .param("st", status).param("now", now.toString()).param("note", note).param("n", name).update();
    }

    /** 报表用：每个没归档变体的一行简介。 */
    public List<String> summaryLines() {
        List<String> out = new ArrayList<>();
        for (Variant v : all()) out.add(v.name() + "（" + statusLabel(v.status()) + "）：" + v.overrides().describe());
        return out;
    }

    public static String statusLabel(String status) {
        return switch (status) {
            case ACTIVE -> "运行中";
            case STOPPED -> "已停开新仓";
            case PROMOTED -> "已转正";
            case ARCHIVED -> "已归档";
            default -> status;
        };
    }
}
