#!/usr/bin/env bash
# 每 5 分钟（systemd 定时器 trendpilot-status.timer）把固定格式的状态摘要推到公开仓库的 server-status 分支
# （用户 2026-10-22 同意公开，并选定"只推摘要"）。摘要字段事先固定，只有数字、时间和状态词：
# 不含日志原文、配置、进程列表或任何密钥。用服务器自己生成的 deploy key（ssh/status_ed25519，私钥不离开服务器）。
# server-status 分支只保留最新一次提交（强制推送）；另外每天一份快照推到 server-status-history 分支（保留 14 天，普通推送）。
# 推送失败只记日志，不影响服务。
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
# "今天" = 越南时间 0 点起（与 V2 日报同一日界），换算成 UTC 再和库里的 UTC 时间比较；不依赖服务器时区
day_start=$(date -u -d "$(TZ=Asia/Ho_Chi_Minh date +%F) 00:00 +07:00" +%Y-%m-%dT%H:%M:%S)
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
    echo "| 账本 | 持仓 | 今天（越南时间）开仓 | 今天平仓 | 累计已实现 U | 未实现 U | 合计 U |"
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
    echo "- 山寨季指标（日期 状态 宽度50% 宽度200% 山寨30日% 比BTC点 连续跑赢天数 ETH/BTC比50日线%）：$(q "SELECT day, state, ROUND(breadth50_pct), ROUND(breadth200_pct), ROUND(alt30_median_pct,1), ROUND(alt_vs_btc30_pct,1), outperform_days, ROUND(ethbtc_vs50_pct,1) FROM alt_season_daily ORDER BY day DESC LIMIT 1;" | tr -cd 'A-Z_0-9 .-' | head -c 120)"
    echo "- V2 参数集（自动转正）：$(q "SELECT IFNULL((SELECT value FROM app_state WHERE key = 'params:V2'), '基准');" | tr -cd 'a-z0-9=.,_-' | head -c 200 | sed 's/^$/基准/')"
    echo "- BTC 短线计划（日期 开盘 不开新一轮 金额倍数）：$plan；第 1 档挂单价：$level0"
    echo "- BTC 短线持仓数量（BTC）：$(num "$(q "SELECT qty FROM btc_daily_state WHERE id = 1;")")，成本 U：$(num "$(q "SELECT ROUND(cost,2) FROM btc_daily_state WHERE id = 1;")")，未实现 U：$(num "$(q "SELECT ROUND(s.qty * (SELECT close FROM kline_15m WHERE venue='BINANCE' AND symbol='BTCUSDT' ORDER BY open_time DESC LIMIT 1) - s.cost, 2) FROM btc_daily_state s WHERE s.id = 1;")")，累计已实现 U：$(num "$(q "SELECT ROUND(realized,2) FROM btc_daily_state WHERE id = 1;")")，今天（越南时间）成交笔数：$(num "$(q "SELECT COUNT(*) FROM btc_daily_trade WHERE voided = 0 AND created_at >= '$day_start';")")"
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
    echo "- 池子状态（池子 资产 状态 上线检查时的 TVL万U 24h成交万U，原有 ETH 池没做检查记 -）：$(q "SELECT GROUP_CONCAT(pool || ' ' || IFNULL(asset, 'ETH') || ' ' || status || ' ' || CASE WHEN tvl_usd IS NULL THEN '-' ELSE printf('%.1f', tvl_usd / 10000) END || ' ' || CASE WHEN volume24h_usd IS NULL THEN '-' ELSE printf('%.1f', volume24h_usd / 10000) END, '，') FROM (SELECT * FROM lp_pool ORDER BY pool);" | tr -cd 'a-zA-Z0-9_ .，-' | head -c 400)"
    # 停用 / 暂不运行的原因（程序生成的固定说明文字，不含密钥；去掉控制字符和表格符号）
    echo "- 未运行池子的原因：$(q "SELECT IFNULL(GROUP_CONCAT(pool || ' ' || status || '：' || IFNULL(note, '-'), '；'), '无') FROM lp_pool WHERE status IN ('INACTIVE', 'REJECTED', 'PENDING');" | tr -d '\000-\037|<>' | head -c 600)"
    echo "- 池子地址：$(q "SELECT IFNULL(GROUP_CONCAT(pool || ' ' || address, '，'), '-') FROM (SELECT pool, address FROM lp_pool WHERE address IS NOT NULL ORDER BY pool);" | tr -cd 'a-zA-Z0-9_ ，-' | head -c 600)"
    echo "- 最近 24 小时覆盖率（池子 有效分钟占比% 有效分钟数 其中有永续报价的分钟数；当天才上线的池子按上线后的分钟数算）：$(q "SELECT GROUP_CONCAT(pool || ' ' || ROUND(100.0 * n / exp, 1) || '% ' || n || ' ' || pn, '，') FROM (SELECT pool, SUM(status = 'OK') AS n, SUM(status = 'OK' AND perp_mark IS NOT NULL) AS pn, MAX(1, MIN(1440, (CAST(strftime('%s', 'now') AS INTEGER) - MIN(ts)) / 60 + 1)) AS exp FROM lp_pool_snapshot WHERE ts >= $lp_since GROUP BY pool ORDER BY pool);" | tr -cd 'a-z0-9_ .%，-' | head -c 400)"
    lp_lp="FROM lp_state s LEFT JOIN lp_pool p ON p.pool = s.pool WHERE s.strategy NOT LIKE '%-hold5050' AND s.strategy NOT LIKE '%-usdc' AND s.strategy NOT LIKE '%-eth' AND s.strategy NOT LIKE '%-btc'"
    for lp_asset in ETH BTC STABLE; do
        lp_best="SELECT s.strategy || ' ' || printf('%+.2f', (json_extract(s.state_json, '\$.lastValue') / 10000.0 - 1) * 100) || '%' $lp_lp AND IFNULL(p.asset, 'ETH') = '$lp_asset' ORDER BY json_extract(s.state_json, '\$.lastValue')"
        lp_bench=$([ "$lp_asset" = STABLE ] && echo usdc || echo hold5050)
        echo "- $lp_asset 组做市策略累计收益最好 / 最差：$(q "$lp_best DESC LIMIT 1;" | tr -cd 'a-zA-Z0-9_ .%+-' | head -c 60) / $(q "$lp_best ASC LIMIT 1;" | tr -cd 'a-zA-Z0-9_ .%+-' | head -c 60)；基准 $lp_bench 平均：$(q "SELECT IFNULL(printf('%+.2f', AVG(json_extract(s.state_json, '\$.lastValue') / 10000.0 - 1) * 100) || '%', '-') FROM lp_state s LEFT JOIN lp_pool p ON p.pool = s.pool WHERE s.strategy LIKE '%-$lp_bench' AND IFNULL(p.asset, 'ETH') = '$lp_asset';" | tr -cd '0-9 .%+-' | head -c 20)"
    done
    echo "- 一次调仓链上成本 U（最新一分钟：60 万 gas × gasPrice + L1 数据费，按 ETH 价格换算）：$(q "SELECT GROUP_CONCAT(pool || ' ' || c || ' ' || src, '，') FROM (SELECT s.pool AS pool, printf('%.4f', (CAST(s.gas_price_wei AS REAL) * 600000 + CAST(s.l1_fee_wei AS REAL)) / 1e18 * COALESCE(s.gas_token_usd, s.perp_mark)) AS c, IFNULL(s.l1_source, 'noL1') AS src FROM lp_pool_snapshot s WHERE s.ts = (SELECT MAX(ts) FROM lp_pool_snapshot t WHERE t.pool = s.pool AND t.status = 'OK') AND s.status = 'OK' ORDER BY s.pool);" | tr -cd 'a-zA-Z0-9_ .，-' | head -c 400)"
    echo "- 各池 60 天起点（UTC）：$(q "SELECT IFNULL(GROUP_CONCAT(pool || ' ' || st, '，'), '-') FROM (SELECT pool, datetime(MIN(json_extract(state_json, '\$.startTs')), 'unixepoch') AS st FROM lp_state GROUP BY pool ORDER BY pool);" | tr -cd 'a-z0-9_ :，-' | head -c 400)"
    echo "- 稳定币脱钩事件（最近 24 小时 DEPEG 条数）：$(num "$(q "SELECT COUNT(*) FROM lp_event WHERE type = 'DEPEG' AND ts >= $lp_since;")")"
    echo "- 第一条 / 最新快照（UTC）：$(q "SELECT IFNULL(datetime(MIN(ts), 'unixepoch'), '-') || ' / ' || IFNULL(datetime(MAX(ts), 'unixepoch'), '-') FROM lp_pool_snapshot;" | tr -cd '0-9 :/-' | head -c 60)"
    echo
    echo "## 私有数据仓库（trendpilot-data）"
    echo
    # 只显示公钥（公开无妨）和最近一次推送的固定格式结果；私钥不读
    echo "- 数据仓库公钥：$(head -c 200 "$APP_DIR/ssh/data_ed25519.pub" 2>/dev/null | tr -cd 'a-zA-Z0-9 +/=@._-' || echo '-')"
    echo "- 数据仓库最近推送：$(head -c 120 "$APP_DIR/logs/data-push.status" 2>/dev/null | tr -cd 'a-zA-Z0-9 :+./_-' || echo '-')"
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

# ---------- 每日历史（server-status-history 分支，周复盘 2026-11-01 R4）
# 每个越南日第一次运行时把上面同一份固定字段摘要另存为 history/<日期>.md，保留最近 14 天（更早的删掉）。
# 每天一个提交，普通推送（不覆盖历史）；推送失败下次运行再推。只有摘要里的固定字段，不含密钥或日志原文。
KEEP_DAYS=14
HIST_DIR="$APP_DIR/status-history-repo"
HIST_BRANCH=server-status-history
today=$(TZ=Asia/Ho_Chi_Minh date +%F)
if [ ! -d "$HIST_DIR/.git" ]; then
    mkdir -p "$HIST_DIR" && chown "$RUN_USER:$RUN_USER" "$HIST_DIR"
    as_user git -C "$HIST_DIR" init -q
    as_user git -C "$HIST_DIR" remote add origin git@github.com:leo14881-eng/TrendPilot.git
    as_user git -C "$HIST_DIR" config user.name "TrendPilot server"
    as_user git -C "$HIST_DIR" config user.email "server@trendpilot.invalid"
    # 远端已有这个分支就接着它写，没有就新建一个独立分支
    if as_user timeout 60 git -C "$HIST_DIR" fetch -q origin "$HIST_BRANCH" 2>>"$APP_DIR/logs/status-push.log"; then
        as_user git -C "$HIST_DIR" checkout -q -B "$HIST_BRANCH" FETCH_HEAD
    else
        as_user git -C "$HIST_DIR" checkout -q --orphan "$HIST_BRANCH"
    fi
fi
if [ ! -f "$HIST_DIR/history/$today.md" ]; then
    mkdir -p "$HIST_DIR/history"
    cp "$REPO_DIR/status.md" "$HIST_DIR/history/$today.md"
    chown -R "$RUN_USER:$RUN_USER" "$HIST_DIR/history"
    cutoff=$(TZ=Asia/Ho_Chi_Minh date -d "-$KEEP_DAYS days" +%F)
    for f in "$HIST_DIR"/history/*.md; do
        [ -e "$f" ] || continue
        d=$(basename "$f" .md)
        if [[ "$d" < "$cutoff" ]]; then as_user git -C "$HIST_DIR" rm -q --cached --ignore-unmatch "history/$d.md"; rm -f "$f"; fi
    done
    as_user git -C "$HIST_DIR" add -A history
    as_user git -C "$HIST_DIR" commit -q -m "status history $today" || true
fi
head_sha=$(as_user git -C "$HIST_DIR" rev-parse -q --verify HEAD 2>/dev/null)
if [ -n "$head_sha" ] && [ "$(cat "$HIST_DIR/.git/last-pushed" 2>/dev/null)" != "$head_sha" ]; then
    if as_user timeout 60 git -C "$HIST_DIR" push -q origin "$HIST_BRANCH" 2>>"$APP_DIR/logs/status-push.log"; then
        echo "$head_sha" > "$HIST_DIR/.git/last-pushed"
    else
        log "状态历史推送失败（见 logs/status-push.log），下次再推"
    fi
fi
