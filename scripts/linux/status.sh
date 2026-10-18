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
echo "--- trendpilot.log 最近警告"
grep -E 'WARN|ERROR' "$APP_DIR/logs/trendpilot.log" 2>/dev/null | tail -n 8 | cut -c1-220
