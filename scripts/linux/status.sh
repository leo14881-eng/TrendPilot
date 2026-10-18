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
echo "--- 运行权（租约）"
grep -E '接管|待命|租约' "$APP_DIR/logs/trendpilot.log" 2>/dev/null | tail -n 5 | cut -c1-220
echo "--- trendpilot.log 最近警告"
grep -E 'WARN|ERROR' "$APP_DIR/logs/trendpilot.log" 2>/dev/null | tail -n 8 | cut -c1-220
