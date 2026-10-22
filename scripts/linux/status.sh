#!/usr/bin/env bash
# 服务器状态：服务、版本、健康、资源、最近日志（不输出配置内容）
# shellcheck source=common.sh
. "$(cd "$(dirname "$0")" && pwd)/common.sh"
echo "主机 $(hostname)  时间 $(date '+%F %T %Z')  $(uptime -p)"
echo "版本 $(cut -c1-7 "$VERSION_FILE" 2>/dev/null)  配置$([ -f "$CONFIG" ] && echo 已到位 || echo 未到位)  数据库$([ -f "$DB" ] && echo 已到位 || echo 未到位)$([ -f "$BIN/disabled.flag" ] && echo '  已停用')"
echo "服务 $(systemctl is-active trendpilot.service)  自动更新 $(systemctl is-active trendpilot-update.timer)"
echo "健康 $(curl -fsS -m 10 "$BASE/api/health" 2>/dev/null | head -c 600)"
free -m | awk 'NR==2{print "内存已用 "$3"M / "$2"M"}'
df -h / | awk 'NR==2{print "硬盘已用 "$3" / "$2}'
# 只读的内存诊断：各进程常驻内存（RSS）前 8，以及 TrendPilot JVM 的堆使用和占用最多的对象类型
echo "--- 进程内存（RSS MB，前 8）"
ps -eo rss=,user=,comm= --sort=-rss | head -n 8 | awk '{printf "  %6.0f %-12s %s\n", $1/1024, $2, $3}'
pid=$(systemctl show -p MainPID --value trendpilot.service 2>/dev/null)
if [ -n "$pid" ] && [ "$pid" != 0 ]; then
    echo "--- TrendPilot 堆（jcmd GC.heap_info）"
    sudo -u "$RUN_USER" timeout 20 jcmd "$pid" GC.heap_info 2>&1 | head -n 12 | sed 's/^/  /'
    echo "--- TrendPilot 对象占用前 15（jcmd GC.class_histogram，会触发一次完整 GC）"
    sudo -u "$RUN_USER" timeout 40 jcmd "$pid" GC.class_histogram 2>&1 | sed -n '1,18p' | sed 's/^/  /'
    echo "--- TrendPilot 堆（完整 GC 后）"
    sudo -u "$RUN_USER" timeout 20 jcmd "$pid" GC.heap_info 2>&1 | head -n 4 | sed 's/^/  /'
fi
echo "--- auto-update.log"
tail -n 8 "$LOG_FILE" 2>/dev/null
echo "--- 最近的任务记录（job_run）"
sqlite3 -readonly "$DB" "SELECT job, started_at, program_status, substr(data_status,1,60) FROM job_run ORDER BY id DESC LIMIT 8;" 2>&1 | head -n 8
q() { sqlite3 -readonly -separator ' ' "$DB" "$1" 2>/dev/null || echo "取不到"; }
echo "--- 账本概况（今天按越南时间）"
day_start=$(date -u -d "$(date +%F) 00:00" +%Y-%m-%dT%H:%M:%S 2>/dev/null)
for pair in V2:position V2D15:v2d_position V1:v1_position V3:v3_position; do
    book=${pair%%:*}; table=${pair#*:}
    echo "$book 持仓 $(q "SELECT COUNT(*) FROM $table;")  今天开仓 $(q "SELECT COUNT(*) FROM shadow_event WHERE book='$book' AND type='BUY' AND created_at >= '$day_start';")  今天平仓 $(q "SELECT COUNT(*) FROM shadow_event WHERE book='$book' AND type='SELL' AND created_at >= '$day_start';")"
done
echo "BTC 短线 持仓 $(q "SELECT IFNULL((SELECT CASE WHEN qty > 0 THEN 1 ELSE 0 END FROM btc_daily_state WHERE id = 1), 0);")  今天成交 $(q "SELECT COUNT(*) FROM btc_daily_trade WHERE voided = 0 AND created_at >= '$day_start';")"
echo "--- BTC 短线：最近 3 天计划（day version strategy regime open atr ema strong carried tp% levels skip_new multiplier reasons）"
q "SELECT day, version, strategy, regime, ROUND(open,2), ROUND(atr,2), ROUND(ema,2), strong, carried, ROUND(tp_pct,3),
        levels, skip_new, multiplier, reasons FROM btc_daily_plan ORDER BY day DESC LIMIT 3;" | sed 's/^/  /'
echo "  当前持仓（qty cost buys last_buy opened_day realized rounds wins）：$(q "SELECT qty, ROUND(cost,2), buys, ROUND(last_buy_price,2), opened_day, ROUND(realized,2), rounds, wins FROM btc_daily_state WHERE id = 1;")"
echo "  最近 5 笔成交（day side level price usdt pnl reason voided）："
q "SELECT day, side, level, ROUND(price,2), ROUND(usdt,2), ROUND(pnl,2), reason, voided FROM btc_daily_trade ORDER BY id DESC LIMIT 5;" | sed 's/^/    /'
utc_start_ms=$(( $(date -u -d "$(date -u +%F) 00:00" +%s) * 1000 ))
echo "  今天（UTC 日）BINANCE BTCUSDT 15m（最低 最高 最新收盘 根数）：$(q "SELECT MIN(low), MAX(high),
        (SELECT close FROM kline_15m WHERE venue='BINANCE' AND symbol='BTCUSDT' ORDER BY open_time DESC LIMIT 1), COUNT(*)
   FROM kline_15m WHERE venue='BINANCE' AND symbol='BTCUSDT' AND open_time >= $utc_start_ms;")"
echo "--- 最近 4 轮 Discovery（信号数 / 决策 / 买入）"
q "SELECT j.started_at, j.data_status,
        (SELECT COUNT(*) FROM signal_log s WHERE s.generation = j.generation),
        (SELECT IFNULL(GROUP_CONCAT(decision || ':' || n, ','), '-') FROM
            (SELECT decision, COUNT(*) n FROM decision_log d WHERE d.generation = j.generation GROUP BY decision)),
        (SELECT COUNT(*) FROM decision_log d WHERE d.generation = j.generation AND d.funded = 1)
   FROM job_run j WHERE j.job = 'DISCOVERY' ORDER BY j.id DESC LIMIT 4;" | sed 's/^/  /'
# 只读的资金诊断：批准了但没买（BUDGET_WAIT）时，看预算占用和风控状态
echo "--- 最近 2 轮批准的买入/加仓（generation symbol action funded score reasons）"
q "SELECT generation, symbol, action, funded, ROUND(score,1), reasons FROM decision_log
   WHERE decision = 'BUY' AND generation IN (SELECT DISTINCT generation FROM decision_log ORDER BY id DESC LIMIT 2)
   ORDER BY id DESC LIMIT 30;" | sed 's/^/  /'
echo "--- V2 资金（持仓数 占用成本 浮动盈亏 已实现盈亏 24小时亏损卖出笔数）"
echo "  $(q "SELECT (SELECT COUNT(*) FROM position), (SELECT ROUND(IFNULL(SUM(CAST(cost_basis AS REAL)),0),2) FROM position),
        (SELECT ROUND(IFNULL(SUM(CAST(mark_net AS REAL)),0),2) FROM position),
        (SELECT ROUND(IFNULL(SUM(CAST(pnl_usdt AS REAL)),0),2) FROM shadow_event WHERE book='V2' AND type='SELL' AND pnl_usdt IS NOT NULL),
        (SELECT COUNT(*) FROM shadow_event WHERE book='V2' AND type='SELL' AND CAST(pnl_usdt AS REAL) < 0
            AND created_at >= strftime('%Y-%m-%dT%H:%M:%S', 'now', '-1 day'));")"
echo "  额度参考：硬顶 20000 × 行情比例（RISK_OFF 0.60 / NEUTRAL 0.75 / CONSTRUCTIVE 0.85 / STRONG 0.95）× 恢复系数（正常 1，减半 0.5）− 占用成本 + 亏损部分"
echo "  V2 各持仓（symbol tranches 成本 浮动净值）：$(q "SELECT GROUP_CONCAT(symbol || ':' || tranches || ':' || ROUND(CAST(cost_basis AS REAL),0) || ':' || ROUND(CAST(mark_net AS REAL),1), '  ') FROM position;")"
echo "--- 风控状态（app_state）"
q "SELECT key, substr(value,1,80) FROM app_state WHERE key LIKE '%regime%' OR key LIKE '%recover%' OR key LIKE '%streak%'
   OR key LIKE '%freeze%' OR key LIKE '%shock%' OR key LIKE '%crash%' ORDER BY key LIMIT 15;" | sed 's/^/  /'
echo "--- 日志里最近的冻结/减半/恢复/预算"
grep -E 'LOSS_FREEZE|LOSS_HALF|RISK_RECOVERY|冻结|减半|预算|BUDGET' "$APP_DIR/logs/trendpilot.log" 2>/dev/null | tail -n 6 | cut -c1-220
echo "--- 状态推送公钥（加到 GitHub 仓库 Deploy keys，允许写入）"
cat "$APP_DIR/ssh/status_ed25519.pub" 2>/dev/null || echo "  还没有生成"
echo "--- 运行权（租约）"
grep -E '接管|待命|租约' "$APP_DIR/logs/trendpilot.log" 2>/dev/null | tail -n 5 | cut -c1-220
echo "--- trendpilot.log 最近警告"
grep -E 'WARN|ERROR' "$APP_DIR/logs/trendpilot.log" 2>/dev/null | tail -n 8 | cut -c1-220
