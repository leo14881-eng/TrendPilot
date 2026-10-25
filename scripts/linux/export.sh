#!/usr/bin/env bash
# 只读数据导出（周复盘用）：用 sqlite3 -readonly 把业务表导成 CSV，打包到 /opt/trendpilot/export/trendpilot-export.tar.gz。
# 只导业务数据：不碰 config/，不导日志，不导 notification_outbox（Telegram 消息原文），app_state 只导 regime 和 risk:* 风控状态。
# 事件类表取最近 16 天，K 线（kline_15m）取最近 9 天；K 线超过上限时只保留出现在信号 / 决策 / 交易 / 涨幅榜复盘里的币和 BTC。
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=common.sh
. "$HERE/common.sh"

OUT_DIR="$APP_DIR/export"
WORK="$OUT_DIR/work"
ARCHIVE="$OUT_DIR/trendpilot-export.tar.gz"
KLINE_MAX_ROWS=${KLINE_MAX_ROWS:-1500000}

[ -f "$DB" ] || { echo "数据库不存在：$DB"; exit 1; }
command -v sqlite3 >/dev/null || { echo "没有 sqlite3"; exit 1; }
rm -rf "$WORK" && mkdir -p "$WORK" || exit 1

SINCE=$(date -u -d '-16 days' +%Y-%m-%dT%H:%M:%S)
KLINE_SINCE_MS=$(( ($(date -u +%s) - 9 * 86400) * 1000 ))
TRACK_SINCE_MS=$(( ($(date -u +%s) - 16 * 86400) * 1000 ))

q() { sqlite3 -readonly "$DB" "$1"; }
has_table() { [ "$(q "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$1';")" = 1 ]; }

MANIFEST="$WORK/manifest.txt"
{
    echo "exported_at_utc $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "version $(cut -c1-7 "$VERSION_FILE" 2>/dev/null)"
    echo "since $SINCE"
    echo "kline_since_ms $KLINE_SINCE_MS"
} > "$MANIFEST"

# exp <name> <SQL>：导出一张 CSV（带表头），记录行数
exp() {
    local name=$1 sql=$2
    if sqlite3 -readonly -header -csv "$DB" "$sql" > "$WORK/$name.csv" 2> "$WORK/.err"; then
        local lines
        lines=$(wc -l < "$WORK/$name.csv")
        echo "$name $((lines > 0 ? lines - 1 : 0))" >> "$MANIFEST"
    else
        echo "$name ERROR $(head -c 200 "$WORK/.err")" >> "$MANIFEST"
        rm -f "$WORK/$name.csv"
    fi
}
# tbl <table> [WHERE ...]：表存在才导
tbl() {
    local t=$1 where=${2:-}
    if has_table "$t"; then exp "$t" "SELECT * FROM $t $where;"; else echo "$t MISSING" >> "$MANIFEST"; fi
}

# 持仓与账本
for t in position v1_position v2d_position v3_position v5_position v3_state loss_guard \
         btc_daily_plan btc_daily_round btc_daily_state btc_daily_trade btc_trend_meta btc_trend_slot btc_trend_trade \
         coin_category coin_identity coin_market unlock_schedule variant_trade missed_replay early_seen; do
    tbl "$t"
done
tbl shadow_event "WHERE created_at >= '$SINCE'"
tbl decision_log "WHERE created_at >= '$SINCE'"
tbl signal_log "WHERE created_at >= '$SINCE'"
tbl job_run "WHERE started_at >= '$SINCE'"
tbl equity_curve "WHERE at >= '$SINCE'"
tbl replay_outcome "WHERE created_at >= '$SINCE'"
tbl fastwatch_observation "WHERE created_at >= '$SINCE'"
tbl research_record "WHERE created_at >= '$SINCE'"
tbl signal_episode "WHERE t_sig >= '$SINCE' OR status <> 'CLOSED'"
if has_table signal_episode; then
    EP="(SELECT id FROM signal_episode WHERE t_sig >= '$SINCE' OR status <> 'CLOSED')"
    tbl signal_outcome "WHERE episode_id IN $EP"
    tbl signal_pullback "WHERE episode_id IN $EP"
    tbl signal_track_bar "WHERE episode_id IN $EP AND bar_close_time >= $TRACK_SINCE_MS"
fi
tbl exit_rule_eval
# 风控状态：只导 regime 和 risk:*（不含 Telegram 偏移、运行权等）
has_table app_state && exp app_state "SELECT key, value FROM app_state WHERE key = 'regime' OR key LIKE 'risk:%';"

# K 线
if has_table kline_15m; then
    n=$(q "SELECT COUNT(*) FROM kline_15m WHERE open_time >= $KLINE_SINCE_MS;")
    echo "kline_15m_all_rows $n" >> "$MANIFEST"
    if [ "${n:-0}" -le "$KLINE_MAX_ROWS" ]; then
        exp kline_15m "SELECT * FROM kline_15m WHERE open_time >= $KLINE_SINCE_MS;"
    else
        exp kline_15m "SELECT * FROM kline_15m WHERE open_time >= $KLINE_SINCE_MS AND (symbol = 'BTCUSDT' OR symbol IN (
            SELECT symbol FROM signal_log WHERE created_at >= '$SINCE' UNION SELECT symbol FROM decision_log WHERE created_at >= '$SINCE'
            UNION SELECT symbol FROM shadow_event WHERE created_at >= '$SINCE' UNION SELECT symbol FROM missed_replay));"
    fi
fi

# 导出的表结构（只有建表语句）
q ".schema" > "$WORK/schema.sql" 2>/dev/null
rm -f "$WORK/.err"

rm -f "$ARCHIVE"
tar -czf "$ARCHIVE" -C "$WORK" . || { echo "打包失败"; exit 1; }
rm -rf "$WORK"
chmod 600 "$ARCHIVE"
echo "导出完成：$ARCHIVE $(du -h "$ARCHIVE" | cut -f1)"
