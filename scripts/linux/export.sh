#!/usr/bin/env bash
# 只读数据导出（周复盘用）：用 sqlite3 -readonly 把业务表导成 CSV，打包到 /opt/trendpilot/export/trendpilot-export.tar.gz。
# 只导业务数据：不碰 config/，不导日志，不导 notification_outbox（Telegram 消息原文），app_state 只导 regime 和 risk:* 风控状态。
# 体积控制在约 10 MB 以内（服务器到电脑的下载较慢）：交易、持仓、账本取最近 16 天；信号、决策取最近 9 天；
# K 线不导 15m 原始数据，改导两份汇总：所有币每个 UTC 日的开高低收（涨幅榜复盘用），
# 以及 BTC、V2 / V2D15 / V5 / V3 交易过的币、每天涨幅前 30 的币的 1h K 线（由 15m 合成）。
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=common.sh
. "$HERE/common.sh"

OUT_DIR="$APP_DIR/export"
WORK="$OUT_DIR/work"
ARCHIVE="$OUT_DIR/trendpilot-export.tar.gz"

[ -f "$DB" ] || { echo "数据库不存在：$DB"; exit 1; }
command -v sqlite3 >/dev/null || { echo "没有 sqlite3"; exit 1; }
rm -rf "$WORK" && mkdir -p "$WORK" || exit 1

SINCE=$(date -u -d '-16 days' +%Y-%m-%dT%H:%M:%S)
RECENT=$(date -u -d '-9 days' +%Y-%m-%dT%H:%M:%S)
KLINE_SINCE_MS=$(( ($(date -u +%s) - 9 * 86400) * 1000 ))

q() { sqlite3 -readonly "$DB" "$1"; }
has_table() { [ "$(q "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$1';")" = 1 ]; }

MANIFEST="$WORK/manifest.txt"
{
    echo "exported_at_utc $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "version $(cut -c1-7 "$VERSION_FILE" 2>/dev/null)"
    echo "since $SINCE"
    echo "recent $RECENT"
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
# 配置化影子变体：登记表、参数集切换、每日评估，以及每个变体自己的持仓表
for t in variant_registry param_switch variant_eval; do
    tbl "$t"
done
if has_table variant_registry; then
    for t in $(sqlite3 -readonly "$DB" "SELECT position_table FROM variant_registry;" 2>/dev/null | grep -E '^[a-z][a-z0-9_]+$'); do
        [ "$t" = v5_position ] || tbl "$t"
    done
fi
tbl shadow_event "WHERE created_at >= '$SINCE'"
tbl decision_log "WHERE created_at >= '$RECENT'"
tbl signal_log "WHERE created_at >= '$RECENT'"
tbl job_run "WHERE started_at >= '$SINCE'"
tbl equity_curve "WHERE at >= '$SINCE'"
tbl replay_outcome "WHERE created_at >= '$RECENT'"
tbl fastwatch_observation "WHERE created_at >= '$RECENT'"
tbl signal_episode "WHERE t_sig >= '$RECENT' OR status <> 'CLOSED'"
if has_table signal_episode; then
    EP="(SELECT id FROM signal_episode WHERE t_sig >= '$RECENT' OR status <> 'CLOSED')"
    tbl signal_outcome "WHERE episode_id IN $EP"
    tbl signal_pullback "WHERE episode_id IN $EP"
fi
# 风控状态：只导 regime 和 risk:*（不含 Telegram 偏移、运行权等）
has_table app_state && exp app_state "SELECT key, value FROM app_state WHERE key = 'regime' OR key LIKE 'risk:%';"

# K 线汇总
if has_table kline_15m; then
    echo "kline_15m_rows $(q "SELECT COUNT(*) FROM kline_15m WHERE open_time >= $KLINE_SINCE_MS;")" >> "$MANIFEST"
    # 先按时间段求首末两根的时间，再取首根开盘价和末根收盘价
    agg() {  # agg <分组时间表达式> <币过滤>
        echo "SELECT g.venue, g.symbol, g.t, f.open, g.high, g.low, l.close, g.quote_volume, g.bars FROM (
                SELECT venue, symbol, $1 AS t, MIN(open_time) AS t0, MAX(open_time) AS t1, MAX(high) AS high, MIN(low) AS low,
                    ROUND(SUM(quote_volume), 0) AS quote_volume, COUNT(*) AS bars
                FROM kline_15m WHERE open_time >= $KLINE_SINCE_MS $2 GROUP BY venue, symbol, t) g
            JOIN kline_15m f ON f.venue = g.venue AND f.symbol = g.symbol AND f.open_time = g.t0
            JOIN kline_15m l ON l.venue = g.venue AND l.symbol = g.symbol AND l.open_time = g.t1"
    }
    DAILY=$(agg "strftime('%Y-%m-%d', open_time / 1000, 'unixepoch')" "")
    exp kline_daily "$DAILY;"
    # 1h K 线：BTC、V2 / V2D15 / V5 / V3 交易过的币、每天每个交易所 UTC 日涨幅前 30 的币
    PICK="AND symbol IN (SELECT 'BTCUSDT' UNION SELECT symbol FROM (SELECT symbol,
                RANK() OVER (PARTITION BY venue, t ORDER BY close / open DESC) AS rk FROM ($DAILY) WHERE open > 0) WHERE rk <= 30
            UNION SELECT symbol FROM shadow_event WHERE book <> 'V1' AND created_at >= '$SINCE')"
    exp kline_1h "$(agg "(open_time / 3600000) * 3600000" "$PICK");"
fi

# 导出的表结构（只有建表语句）
q ".schema" > "$WORK/schema.sql" 2>/dev/null
rm -f "$WORK/.err"

(cd "$WORK" && du -k -- *.csv) | sort -rn | head -n 12 | sed 's/^/size_kb /' >> "$MANIFEST"
rm -f "$ARCHIVE"
tar -C "$WORK" -cf - . | gzip -9 > "$ARCHIVE" || { echo "打包失败"; exit 1; }
rm -rf "$WORK"
chmod 600 "$ARCHIVE"
echo "导出完成：$ARCHIVE $(du -h "$ARCHIVE" | cut -f1)"
