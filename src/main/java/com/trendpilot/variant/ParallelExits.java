package com.trendpilot.variant;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.portfolio.Position;
import com.trendpilot.tracking.ExitRule;
import com.trendpilot.tracking.TrackingProperties;

/**
 * 候选退出规则的影子并行（设计文档 8.2 第 ③ 阶段）：每条候选规则复用 V2 的每一笔买入，只有退出不同。
 * <ul>
 * <li>候选规则叠加在现行规则之上：V2 正式卖出时，仍持有的变体按同一成交一起卖出；规则先触发时变体按本轮 V2 的
 *     全数量模拟成交提前卖出。变体卖出后同一持仓后来的加仓不再跟随。</li>
 * <li>只写 variant_trade，不改正式账本、不占资金池。规则列表在 trendpilot.tracking.parallel-rules，默认空。</li>
 * </ul>
 */
@Component
public class ParallelExits {

    private final JdbcClient jdbc;
    private final List<ExitRule> rules;

    public ParallelExits(JdbcClient jdbc, TrackingProperties p) {
        this.jdbc = jdbc;
        this.rules = p.parallelRules() == null ? List.of() : p.parallelRules().stream().map(ExitRule::byId).toList();
    }

    public List<ExitRule> rules() {
        return rules;
    }

    /** V2 本轮没有卖出：更新每个变体，规则触发的提前卖出。 */
    public void observe(Position pos, ExitDecision d, double atrPct, Instant now) {
        double cost = d.updated().costBasis().doubleValue();
        double rNet = d.netPnl().doubleValue() / cost * 100;
        double hold = Duration.between(pos.openedAt(), now).toMinutes() / 60.0;
        for (ExitRule r : rules) {
            jdbc.sql("""
                    INSERT OR IGNORE INTO variant_trade(rule_id, venue, symbol, opened_at, status, peak_net_pct)
                    VALUES (:r, :v, :s, :o, 'OPEN', :peak)
                    """).param("r", r.id()).param("v", pos.venue().name()).param("s", pos.symbol())
                    .param("o", pos.openedAt().toString()).param("peak", rNet).update();
            Double peak = jdbc.sql("""
                    SELECT peak_net_pct FROM variant_trade WHERE rule_id = :r AND venue = :v AND symbol = :s
                        AND opened_at = :o AND status = 'OPEN'
                    """).param("r", r.id()).param("v", pos.venue().name()).param("s", pos.symbol())
                    .param("o", pos.openedAt().toString()).query(Double.class).optional().orElse(null);
            if (peak == null) continue; // 已经提前卖出
            peak = Math.max(peak, rNet);
            // 相对最高点的回撤：用净收益换算（与离线回放的收盘价回撤口径近似）
            double dd = (1 - (1 + rNet / 100) / (1 + peak / 100)) * 100;
            String hit = r.check(new ExitRule.State(rNet, d.rawReturnPct(), hold, peak, dd, atrPct));
            if (hit != null) {
                close(r.id(), pos, "CLOSED", hit, d.netPnl().doubleValue(), cost, now);
            } else {
                jdbc.sql("""
                        UPDATE variant_trade SET peak_net_pct = :p WHERE rule_id = :r AND venue = :v AND symbol = :s
                            AND opened_at = :o
                        """).param("p", peak).param("r", r.id()).param("v", pos.venue().name())
                        .param("s", pos.symbol()).param("o", pos.openedAt().toString()).update();
            }
        }
    }

    /** V2 正式卖出：记录基准结果；仍持有的变体按同一成交一起卖出。 */
    public void closeWithV2(Position pos, ExitDecision d, Instant now) {
        double cost = pos.costBasis().doubleValue();
        for (ExitRule r : rules) {
            jdbc.sql("""
                    INSERT OR IGNORE INTO variant_trade(rule_id, venue, symbol, opened_at, status, peak_net_pct)
                    VALUES (:r, :v, :s, :o, 'OPEN', 0)
                    """).param("r", r.id()).param("v", pos.venue().name()).param("s", pos.symbol())
                    .param("o", pos.openedAt().toString()).update();
            jdbc.sql("""
                    UPDATE variant_trade SET b0_pnl = :pnl, b0_cost = :cost WHERE rule_id = :r AND venue = :v
                        AND symbol = :s AND opened_at = :o
                    """).param("pnl", d.netPnl().doubleValue()).param("cost", cost).param("r", r.id())
                    .param("v", pos.venue().name()).param("s", pos.symbol()).param("o", pos.openedAt().toString())
                    .update();
            close(r.id(), pos, "CLOSED", d.reason(), d.netPnl().doubleValue(), cost, now);
        }
    }

    private void close(String rule, Position pos, String status, String reason, double pnl, double cost, Instant now) {
        jdbc.sql("""
                UPDATE variant_trade SET status = :st, exit_reason = :reason, exit_at = :t, net_pnl = :pnl, cost_usdt = :cost,
                    hold_minutes = :hold
                WHERE rule_id = :r AND venue = :v AND symbol = :s AND opened_at = :o AND status = 'OPEN'
                """).param("st", status).param("reason", reason).param("t", now.toString()).param("pnl", pnl)
                .param("cost", cost).param("hold", Duration.between(pos.openedAt(), now).toMinutes())
                .param("r", rule).param("v", pos.venue().name()).param("s", pos.symbol())
                .param("o", pos.openedAt().toString()).update();
    }

    /** 一条候选规则与 V2 在同一批已结束持仓上的配对比较。 */
    public record Comparison(String ruleId, int pairs, double variantMeanPct, double v2MeanPct, double variantPnl,
                             double v2Pnl, int earlyExits) {
        public String line() {
            return String.format(Locale.ROOT, "· %s：%d 笔配对，每笔 %.2f%% vs V2 %.2f%%，合计 %.2f U vs %.2f U，提前卖出 %d 笔",
                    ruleId, pairs, variantMeanPct, v2MeanPct, variantPnl, v2Pnl, earlyExits);
        }
    }

    public List<Comparison> compare() {
        List<Comparison> out = new ArrayList<>();
        for (ExitRule r : rules) {
            record Row(double pnl, double cost, double b0, double b0Cost, String reason) {
            }
            List<Row> rows = jdbc.sql("""
                    SELECT net_pnl, cost_usdt, b0_pnl, b0_cost, exit_reason FROM variant_trade
                    WHERE rule_id = :r AND status = 'CLOSED' AND b0_pnl IS NOT NULL
                    """).param("r", r.id())
                    .query((rs, i) -> new Row(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                            rs.getString(5)))
                    .list();
            out.add(new Comparison(r.id(), rows.size(),
                    rows.stream().mapToDouble(x -> x.pnl() / x.cost() * 100).average().orElse(Double.NaN),
                    rows.stream().mapToDouble(x -> x.b0() / x.b0Cost() * 100).average().orElse(Double.NaN),
                    rows.stream().mapToDouble(Row::pnl).sum(), rows.stream().mapToDouble(Row::b0).sum(),
                    (int) rows.stream().filter(x -> x.reason().equals(r.id())).count()));
        }
        return out;
    }
}
