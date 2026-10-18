#!/usr/bin/env bash
# 迁移接收：配置和数据库已上传到 /opt/trendpilot 后执行。校验数据库、修正权限、启用并启动服务，等到一次成功的 Monitor。
# 电脑停机后它在 Telegram 的租约约 10 分钟后过期，服务器这之后才接管，所以最多等 25 分钟。
# 没等到就停掉服务器上的服务并退出 1（电脑端会恢复本机运行）。
set -uo pipefail
# shellcheck source=common.sh
. "$(cd "$(dirname "$0")" && pwd)/common.sh"
[ -f "$CONFIG" ] && [ -f "$DB" ] || { echo "配置或数据库没有到位"; exit 1; }
chmod 600 "$CONFIG"
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR"
check=$(sudo -u "$RUN_USER" sqlite3 "$DB" 'PRAGMA integrity_check;' 2>&1)
[ "$check" = ok ] || { echo "数据库校验没通过：$check"; exit 1; }
rm -f "$BIN/disabled.flag"
APP_LOG="$APP_DIR/logs/trendpilot.log"
takeovers() { local n; n=$(grep -c '负责运行（原运行机器' "$APP_LOG" 2>/dev/null); echo "${n:-0}"; }
before=$(takeovers)
restarted=0
since=$(date +%s)
systemctl restart trendpilot.service
for _ in $(seq 1 300); do
    sleep 5
    if [ "$(last_monitor_ok)" -ge "$since" ]; then
        echo "服务器上的服务已运行，Monitor 正常"
        exit 0
    fi
    # 以待命身份启动、中途接管运行的进程，Monitor 不会恢复（2026-10-18 迁移时电脑和服务器都出现过）。
    # 看到接管后重启一次：租约已经是本机的，重启后直接以运行身份启动。
    if [ "$restarted" -eq 0 ] && [ "$(takeovers)" -gt "$before" ]; then
        echo "已接管运行，重启一次让定时任务完整启动"
        restarted=1
        since=$(date +%s)
        systemctl restart trendpilot.service
    fi
done
echo "启动后 25 分钟内没有成功的 Monitor"
systemctl stop trendpilot.service
touch "$BIN/disabled.flag"
exit 1
