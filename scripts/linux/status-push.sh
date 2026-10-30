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
    local realized unreal
    realized=$(num "$(q "SELECT ROUND(IFNULL(SUM(CAST(pnl_usdt AS REAL)),0),2) FROM shadow_event WHERE book='$name' AND type='SELL' AND pnl_usdt IS NOT NULL;")")
    unreal=$(num "$(q "SELECT ROUND(IFNULL(SUM(CAST(mark_net AS REAL)),0),2) FROM $table;")")
    echo "| $name | $(num "$(q "SELECT COUNT(*) FROM $table;")") | $(num "$(q "SELECT COUNT(*) FROM shadow_event WHERE book='$name' AND type='BUY' AND created_at >= '$day_start';")") | $(num "$(q "SELECT COUNT(*) FROM shadow_event WHERE book='$name' AND type='SELL' AND created_at >= '$day_start';")") | $realized | $unreal | $(awk -v a="$realized" -v b="$unreal" 'BEGIN{if (a=="?"||b=="?") print "?"; else printf "%.2f", a+b}') |"
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
    echo "| 账本 | 持仓 | 今天开仓 | 今天平仓 | 累计已实现 U | 未实现 U | 合计 U |"
    echo "|---|---:|---:|---:|---:|---:|---:|"
    book V2 position
    book V2D15 v2d_position
    # 配置化影子变体（V5、V6…）从登记表自动列出；旧数据库没有登记表时只列 V5
    variants=$(sqlite3 -readonly -separator ' ' "$DB" "SELECT name, position_table FROM variant_registry WHERE status != 'ARCHIVED' ORDER BY LENGTH(name), name;" 2>/dev/null | grep -E '^V[0-9A-Z]+ [a-z0-9_]+$')
    [ -n "$variants" ] || variants="V5 v5_position"
    while read -r vname vtable; do
        [ -n "$vname" ] && book "$vname" "$vtable"
    done <<< "$variants"
    book V1 v1_position
    book V3 v3_position
    echo
    echo "- 影子变体风控（各自的亏损冻结 / 减半 / 恢复期）：$(q "SELECT GROUP_CONCAT(r.name || ' ' || IFNULL((SELECT value FROM app_state WHERE key = 'risk:' || r.name || ':status'), 'NORMAL'), ', ') FROM variant_registry r WHERE r.status != 'ARCHIVED';" | tr -cd 'A-Z0-9_ ,' | head -c 300)"
    echo "- V2 参数集（自动转正）：$(q "SELECT IFNULL((SELECT value FROM app_state WHERE key = 'params:V2'), '基准');" | tr -cd 'a-z0-9=.,_-' | head -c 200 | sed 's/^$/基准/')"
    echo "- BTC 短线计划（日期 开盘 不开新一轮 金额倍数）：$plan；第 1 档挂单价：$level0"
    echo "- BTC 短线持仓数量（BTC）：$(num "$(q "SELECT qty FROM btc_daily_state WHERE id = 1;")")，成本 U：$(num "$(q "SELECT ROUND(cost,2) FROM btc_daily_state WHERE id = 1;")")，未实现 U：$(num "$(q "SELECT ROUND(s.qty * (SELECT close FROM kline_15m WHERE venue='BINANCE' AND symbol='BTCUSDT' ORDER BY open_time DESC LIMIT 1) - s.cost, 2) FROM btc_daily_state s WHERE s.id = 1;")")，累计已实现 U：$(num "$(q "SELECT ROUND(realized,2) FROM btc_daily_state WHERE id = 1;")")，今天成交笔数：$(num "$(q "SELECT COUNT(*) FROM btc_daily_trade WHERE voided = 0 AND created_at >= '$day_start';")")"
    echo "- 今天（UTC 日）BTCUSDT 最低 / 最高：$(q "SELECT MIN(low), MAX(high) FROM kline_15m WHERE venue='BINANCE' AND symbol='BTCUSDT' AND open_time >= $utc_start_ms;")"
    echo "- 最近一轮扫描（时间 数据状态 批准 实际买入）：$(q "SELECT j.started_at, j.data_status,
        (SELECT COUNT(*) FROM decision_log d WHERE d.generation = j.generation AND d.decision = 'BUY'),
        (SELECT COUNT(*) FROM decision_log d WHERE d.generation = j.generation AND d.funded = 1)
        FROM job_run j WHERE j.job = 'DISCOVERY' ORDER BY j.id DESC LIMIT 1;")"
    echo
    echo "## BTC 均线趋势（单独账本，影子）"
    echo
    echo "- 各份（均线 状态 USDT BTC 最近信号日）：$(q "SELECT GROUP_CONCAT('SMA' || sma || ' ' || state || ' ' || ROUND(usdt,2) || ' ' || ROUND(btc,6) || ' ' || IFNULL(last_signal_day,'-'), '；') FROM btc_trend_slot;")"
    echo "- 总价值 U（按最新价）：$(q "SELECT ROUND(SUM(CASE WHEN state = 'BTC' THEN btc * (SELECT CAST(value AS REAL) FROM btc_trend_meta WHERE key = 'last_price') ELSE usdt END), 2) FROM btc_trend_slot;")"
    echo "- 信号日 / 收盘 / SMA75 / SMA100 / SMA125 / SMA150：$(q "SELECT GROUP_CONCAT(value, ' / ') FROM (SELECT value FROM btc_trend_meta WHERE key IN ('signal_day','signal_close','sma75','sma100','sma125','sma150') ORDER BY CASE key WHEN 'signal_day' THEN 0 WHEN 'signal_close' THEN 1 WHEN 'sma75' THEN 2 WHEN 'sma100' THEN 3 WHEN 'sma125' THEN 4 ELSE 5 END);")"
    echo "- 影子成交笔数：$(num "$(q "SELECT COUNT(*) FROM btc_trend_trade;")")"
    echo
    echo "## LP 影子盘（只读影子）"
    echo
    lp_since=$(( $(date -u +%s) - 86400 ))
    echo "- 池子状态：$(q "SELECT GROUP_CONCAT(pool || ' ' || status, '，') FROM (SELECT pool, status FROM lp_pool ORDER BY pool);" | tr -cd 'a-zA-Z0-9_ ，-' | head -c 200)"
    echo "- 最近 24 小时覆盖率（池子 有效分钟占比% 有效分钟数 其中有永续报价的分钟数）：$(q "SELECT GROUP_CONCAT(pool || ' ' || ROUND(100.0 * n / 1440, 1) || '% ' || n || ' ' || pn, '，') FROM (SELECT pool, SUM(status = 'OK') AS n, SUM(status = 'OK' AND perp_mark IS NOT NULL) AS pn FROM lp_pool_snapshot WHERE ts >= $lp_since GROUP BY pool ORDER BY pool);" | tr -cd 'a-z0-9_ .%，-' | head -c 200)"
    lp_best="SELECT strategy || ' ' || printf('%+.2f', (json_extract(state_json, '\$.lastValue') / 10000.0 - 1) * 100) || '%' FROM lp_state WHERE strategy LIKE '%-w%' ORDER BY json_extract(state_json, '\$.lastValue')"
    echo "- 做市策略累计收益最好 / 最差：$(q "$lp_best DESC LIMIT 1;" | tr -cd 'a-zA-Z0-9_ .%+-' | head -c 60) / $(q "$lp_best ASC LIMIT 1;" | tr -cd 'a-zA-Z0-9_ .%+-' | head -c 60)；持有 50/50 平均：$(q "SELECT printf('%+.2f', AVG(json_extract(state_json, '\$.lastValue') / 10000.0 - 1) * 100) || '%' FROM lp_state WHERE strategy LIKE '%-hold5050';" | tr -cd '0-9 .%+-' | head -c 20)"
    echo "- 第一条 / 最新快照（UTC）：$(q "SELECT IFNULL(datetime(MIN(ts), 'unixepoch'), '-') || ' / ' || IFNULL(datetime(MAX(ts), 'unixepoch'), '-') FROM lp_pool_snapshot;" | tr -cd '0-9 :/-' | head -c 60)"
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
