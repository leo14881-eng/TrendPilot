package com.trendpilot.variant;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.trendpilot.job.AppState;
import com.trendpilot.portfolio.ShadowBook;

/**
 * V2 当前采用的参数集 = application.yml 基准 + 已转正的参数改动（app_state 'params:V2'）。
 * 没有转正过（或记录读不懂）时就是基准本身，V2 的行为和没有这个功能时完全一样。
 * 每次切换（自动转正、回滚）写 param_switch，日报显示；docs/策略变更记录.md 在下次复盘时补上。
 * 只管 V2 这个影子账本：BTC 每日短线、BTC 均线趋势、V1、V3 不经过这里。
 */
@Component
public class ParamSets {

    private static final Logger log = LoggerFactory.getLogger(ParamSets.class);
    public static final String KEY = "params:" + ShadowBook.BOOK;

    private final JdbcClient jdbc;
    private final AppState state;
    private final OptimizeProperties opt;

    public ParamSets(JdbcClient jdbc, AppState state, OptimizeProperties opt) {
        this.jdbc = jdbc;
        this.state = state;
        this.opt = opt;
    }

    /** V2 现在叠加在基准上的参数改动；读不到或读不懂时按基准（不改）。 */
    public VariantOverrides current() {
        try {
            return state.get(KEY).filter(s -> !s.isBlank()).map(VariantOverrides::parseInline).orElse(VariantOverrides.NONE);
        } catch (RuntimeException e) {
            log.warn("V2 参数集记录读不懂，按基准运行：{}", e.getMessage());
            return VariantOverrides.NONE;
        }
    }

    /** 最近一次正式改动：配置里的日期（人工改规则）和最近一次参数集切换，取较晚的。 */
    public Instant lastFormalChange() {
        Instant cfg = LocalDate.parse(opt.lastFormalChange()).atStartOfDay(ZoneOffset.UTC).toInstant();
        Optional<Instant> sw = jdbc.sql("SELECT MAX(created_at) FROM param_switch WHERE book = :b").param("b", ShadowBook.BOOK)
                .query(String.class).optional().filter(s -> s != null && !s.isBlank()).map(Instant::parse);
        return sw.filter(s -> s.isAfter(cfg)).orElse(cfg);
    }

    /** 正式规则最多每 formalChangeDays 天换一次。 */
    public boolean changeAllowed(Instant now) {
        return !now.isBefore(nextChangeAllowed());
    }

    public Instant nextChangeAllowed() {
        return lastFormalChange().plus(java.time.Duration.ofDays(opt.formalChangeDays()));
    }

    /** 转正：V2 改用 to 这组参数（相对基准），记录切换。调用方负责检查两周限制和安全边界。 */
    public synchronized void promote(String variant, VariantOverrides to, Instant now, String note) {
        String from = current().inline();
        jdbc.sql("""
                INSERT INTO param_switch(book, kind, variant, from_json, to_json, created_at, note)
                VALUES (:b, 'PROMOTE', :v, :f, :t, :now, :note)
                """).param("b", ShadowBook.BOOK).param("v", variant).param("f", from).param("t", to.inline())
                .param("now", now.toString()).param("note", note).update();
        state.put(KEY, to.inline());
        log.info("V2 参数集切换为 {}（{}）：{}", variant, to.inline(), note);
    }

    /** 一次切换记录。 */
    public record Switch(long id, String kind, String variant, String from, String to, Instant at, boolean rolledBack) {
    }

    /** 回滚到上一个参数集：最近一次没被回滚过的转正。没有可回滚的返回空。回滚不受两周限制。 */
    public Optional<Switch> rollback(Instant now) {
        return rollback(now, null);
    }

    /**
     * 同上；updateId = 触发回滚的 Telegram 消息编号。同一条消息只执行一次：再次处理时返回当时撤销的那次转正，不再多撤。
     * 三步写库（标记已回滚、记录回滚、切换参数集）在一个事务里，中途出错整体不生效。
     */
    @org.springframework.transaction.annotation.Transactional
    public synchronized Optional<Switch> rollback(Instant now, Long updateId) {
        if (updateId != null) {
            Optional<Switch> done = jdbc.sql("""
                    SELECT id, kind, variant, to_json, from_json, created_at, rolled_back FROM param_switch
                    WHERE kind = 'ROLLBACK' AND update_id = :u
                    """).param("u", updateId).query((rs, i) -> new Switch(rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), Instant.parse(rs.getString(6)), rs.getInt(7) == 1)).optional();
            if (done.isPresent()) {
                log.info("回滚指令 {} 已经执行过，不重复回滚", updateId);
                return done;
            }
        }
        Optional<Switch> last = jdbc.sql("""
                SELECT id, kind, variant, from_json, to_json, created_at, rolled_back FROM param_switch
                WHERE book = :b AND kind = 'PROMOTE' AND rolled_back = 0 ORDER BY id DESC LIMIT 1
                """).param("b", ShadowBook.BOOK).query((rs, i) -> new Switch(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), Instant.parse(rs.getString(6)), rs.getInt(7) == 1)).optional();
        if (last.isEmpty()) return Optional.empty();
        Switch s = last.get();
        jdbc.sql("UPDATE param_switch SET rolled_back = 1 WHERE id = :id").param("id", s.id()).update();
        jdbc.sql("""
                INSERT INTO param_switch(book, kind, variant, from_json, to_json, created_at, note, update_id)
                VALUES (:b, 'ROLLBACK', :v, :f, :t, :now, '用户回复回滚', :u)
                """).param("b", ShadowBook.BOOK).param("v", s.variant()).param("f", current().inline()).param("t", s.from())
                .param("now", now.toString()).param("u", updateId).update();
        state.put(KEY, s.from());
        log.info("V2 参数集已回滚：撤销 {} 的转正，恢复为 {}", s.variant(), s.from().isBlank() ? "基准" : s.from());
        return last;
    }

    public java.util.List<Switch> history(int limit) {
        return jdbc.sql("""
                SELECT id, kind, variant, from_json, to_json, created_at, rolled_back FROM param_switch
                WHERE book = :b ORDER BY id DESC LIMIT :n
                """).param("b", ShadowBook.BOOK).param("n", limit).query((rs, i) -> new Switch(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), Instant.parse(rs.getString(6)), rs.getInt(7) == 1)).list();
    }

    /** 日报一行：V2 现在用哪组参数。 */
    public String line() {
        VariantOverrides o = current();
        if (o.isEmpty()) return "V2 参数集：基准（application.yml，没有自动转正过的改动）";
        String from = history(10).stream().filter(s -> s.kind().equals("PROMOTE") && !s.rolledBack()).map(Switch::variant)
                .findFirst().orElse("?");
        return "V2 参数集：基准 + " + from + " 的改动（" + o.describe() + "）；回复「回滚」恢复上一个参数集";
    }
}
