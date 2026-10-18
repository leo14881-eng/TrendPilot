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
echo "--- 最近 4 轮 Discovery（信号数 / 决策 / 买入）"
q "SELECT j.started_at, j.data_status,
        (SELECT COUNT(*) FROM signal_log s WHERE s.generation = j.generation),
        (SELECT IFNULL(GROUP_CONCAT(decision || ':' || n, ','), '-') FROM
            (SELECT decision, COUNT(*) n FROM decision_log d WHERE d.generation = j.generation GROUP BY decision)),
        (SELECT COUNT(*) FROM decision_log d WHERE d.generation = j.generation AND d.funded = 1)
   FROM job_run j WHERE j.job = 'DISCOVERY' ORDER BY j.id DESC LIMIT 4;" | sed 's/^/  /'
echo "--- 运行权（租约）"
grep -E '接管|待命|租约' "$APP_DIR/logs/trendpilot.log" 2>/dev/null | tail -n 5 | cut -c1-220
echo "--- trendpilot.log 最近警告"
grep -E 'WARN|ERROR' "$APP_DIR/logs/trendpilot.log" 2>/dev/null | tail -n 8 | cut -c1-220
