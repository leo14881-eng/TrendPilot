#!/usr/bin/env bash
# 每 5 分钟（systemd 定时器 trendpilot-status.timer）把固定格式的状态摘要推到公开仓库的 server-status 分支
# （用户 2026-10-22 同意公开，并选定"只推摘要"）。摘要字段事先固定，只有数字、时间和状态词：
# 不含日志原文、配置、进程列表或任何密钥。用服务器自己生成的 deploy key（ssh/status_ed25519，私钥不离开服务器）。
# 分支只保留最新一次提交（强制推送）。推送失败只记日志，不影响服务。
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=common.sh
. "$HERE/common.sh"

KEY="$APP_DIR/ssh/status_ed25519"
REPO_DIR="$APP_DIR/status-repo"
[ -f "$KEY" ] || exit 0

q() { sqlite3 -readonly -separator ' ' "$DB" "$1" 2>/dev/null || echo "?"; }
num() { case "$1" in ''|*[!0-9.-]*) echo "?" ;; *) echo "$1" ;; esac; }

health=$(curl -fsS -m 10 "$BASE/api/health" 2>/dev/null)
hv() { echo "$health" | jq -r ".$1 // \"?\"" 2>/dev/null | head -c 40; }
day_start=$(date -u -d "$(date +%F) 00:00" +%Y-%m-%dT%H:%M:%S)
utc_start_ms=$(( $(date -u -d "$(date -u +%F) 00:00" +%s) * 1000 ))
since_1h=$(date -d '-1 hour' '+%Y-%m-%dT%H')

book() {
    local name=$1 table=$2
    echo "| $name | $(num "$(q "SELECT COUNT(*) FROM $table;")") | $(num "$(q "SELECT COUNT(*) FROM shadow_event WHERE book='$name' AND type='BUY' AND created_at >= '$day_start';")") | $(num "$(q "SELECT COUNT(*) FROM shadow_event WHERE book='$name' AND type='SELL' AND created_at >= '$day_start';")") | $(num "$(q "SELECT ROUND(IFNULL(SUM(CAST(pnl_usdt AS REAL)),0),2) FROM shadow_event WHERE book='$name' AND type='SELL' AND pnl_usdt IS NOT NULL;")") |"
}

plan=$(q "SELECT day, ROUND(open,2), skip_new, multiplier FROM btc_daily_plan ORDER BY day DESC LIMIT 1;")
level0=$(q "SELECT levels FROM btc_daily_plan ORDER BY day DESC LIMIT 1;" | sed -E 's/^0:([0-9.]+):.*/\1/; t; s/.*/-/')
errors=$(awk -v s="$since_1h" '$1 >= s && /( WARN | ERROR )/' "$APP_DIR/logs/trendpilot.log" 2>/dev/null | wc -l)

summary() {
    echo "# TrendPilot 服务器状态摘要"
    echo
    echo "- 生成时间：$(date '+%F %T %Z')"
    echo "- 版本：$(cut -c1-7 "$VERSION_FILE" 2>/dev/null)"
    echo "- 服务：$(systemctl is-active trendpilot.service)，自动更新：$(systemctl is-active trendpilot-update.timer)"
    echo "- 健康：ok=$(hv ok)，最近成功 Monitor $(hv lastMonitorOk)，最近 Discovery $(hv lastDiscovery)，运行 $(hv uptimeMinutes) 分钟"
    echo "- 内存：$(free -m | awk 'NR==2{print $3"M / "$2"M"}')，硬盘：$(df -h / | awk 'NR==2{print $3" / "$2" ("$5")"}')"
    echo "- 最近 1 小时 WARN/ERROR 条数：$(num "$errors")"
    echo
    echo "| 账本 | 持仓 | 今天开仓 | 今天平仓 | 累计已实现 U |"
    echo "|---|---:|---:|---:|---:|"
    book V2 position
    book V2D15 v2d_position
    book V1 v1_position
    book V3 v3_position
    echo
    echo "- BTC 短线计划（日期 开盘 不开新一轮 金额倍数）：$plan；第 1 档挂单价：$level0"
    echo "- BTC 短线持仓数量（BTC）：$(num "$(q "SELECT qty FROM btc_daily_state WHERE id = 1;")")，累计已实现 U：$(num "$(q "SELECT ROUND(realized,2) FROM btc_daily_state WHERE id = 1;")")，今天成交笔数：$(num "$(q "SELECT COUNT(*) FROM btc_daily_trade WHERE voided = 0 AND created_at >= '$day_start';")")"
    echo "- 今天（UTC 日）BTCUSDT 最低 / 最高：$(q "SELECT MIN(low), MAX(high) FROM kline_15m WHERE venue='BINANCE' AND symbol='BTCUSDT' AND open_time >= $utc_start_ms;")"
    echo "- 最近一轮扫描（时间 数据状态 批准 实际买入）：$(q "SELECT j.started_at, j.data_status,
        (SELECT COUNT(*) FROM decision_log d WHERE d.generation = j.generation AND d.decision = 'BUY'),
        (SELECT COUNT(*) FROM decision_log d WHERE d.generation = j.generation AND d.funded = 1)
        FROM job_run j WHERE j.job = 'DISCOVERY' ORDER BY j.id DESC LIMIT 1;")"
}

as_user() { sudo -u "$RUN_USER" -H env GIT_SSH_COMMAND="ssh -i $KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$APP_DIR/ssh/known_hosts" "$@"; }

if [ ! -d "$REPO_DIR/.git" ]; then
    mkdir -p "$REPO_DIR" && chown "$RUN_USER:$RUN_USER" "$REPO_DIR"
    as_user git -C "$REPO_DIR" init -q
    as_user git -C "$REPO_DIR" checkout -q -b server-status
    as_user git -C "$REPO_DIR" remote add origin git@github.com:leo14881-eng/TrendPilot.git
    as_user git -C "$REPO_DIR" config user.name "TrendPilot server"
    as_user git -C "$REPO_DIR" config user.email "server@trendpilot.invalid"
fi
summary > "$REPO_DIR/status.md"
chown "$RUN_USER:$RUN_USER" "$REPO_DIR/status.md"
as_user git -C "$REPO_DIR" add status.md
if as_user git -C "$REPO_DIR" rev-parse -q --verify HEAD >/dev/null; then
    as_user git -C "$REPO_DIR" commit -q --amend -m "status $(date '+%F %T %Z')"
else
    as_user git -C "$REPO_DIR" commit -q -m "status $(date '+%F %T %Z')"
fi
as_user timeout 60 git -C "$REPO_DIR" push -q -f origin server-status 2>>"$APP_DIR/logs/status-push.log" \
    || log "状态摘要推送失败（见 logs/status-push.log）"
