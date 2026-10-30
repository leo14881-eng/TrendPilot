#!/usr/bin/env bash
# TrendPilot 服务器（Ubuntu）共用设置和函数。被 install.sh / auto-update.sh 引用。
# 从不输出 config/application-local.yml 的内容。

APP_DIR=/opt/trendpilot
SRC_DIR=/opt/trendpilot-src
REPO_URL=https://github.com/leo14881-eng/TrendPilot.git
BRANCH=main
RUN_USER=trendpilot
PORT=8080
BASE="http://127.0.0.1:$PORT"
BIN="$APP_DIR/app"
JAR="$BIN/trendpilot.jar"
PREV_JAR="$BIN/trendpilot.prev.jar"
VERSION_FILE="$BIN/version.txt"
FAILED_FILE="$BIN/failed-commit.txt"
MON_FAILED_FILE="$BIN/monitor-failed-commit.txt"
CONFIG="$APP_DIR/config/application-local.yml"
DB="$APP_DIR/data/trendpilot.db"
LOG_FILE="$APP_DIR/logs/auto-update.log"

log() {
    mkdir -p "$(dirname "$LOG_FILE")"
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG_FILE"
}

read_text() {
    [ -f "$1" ] && tr -d '\r\n ' < "$1" || true
}

# Telegram 通知：从本机配置读取机器人 token 和 chat id（不输出）。读不到就不发。
send_telegram() {
    [ -f "$CONFIG" ] || return 0
    local token chat text
    token=$(sed -n 's/^[[:space:]]*bot-token:[[:space:]]*["'\'']\{0,1\}\([^"'\''[:space:]#]*\).*/\1/p' "$CONFIG" | head -1)
    chat=$(sed -n 's/^[[:space:]]*chat-id:[[:space:]]*["'\'']\{0,1\}\([^"'\''[:space:]#]*\).*/\1/p' "$CONFIG" | head -1)
    [ -n "$token" ] && [ -n "$chat" ] || return 0
    text="【TrendPilot】$1"
    curl -fsS -m 20 -o /dev/null "https://api.telegram.org/bot${token}/sendMessage" \
        --data-urlencode "chat_id=${chat}" --data-urlencode "text=${text}" 2>/dev/null \
        || log "Telegram 发送失败"
}

healthy() {
    curl -fsS -m 15 -o /dev/null "$BASE/api/status" 2>/dev/null
}

# /api/health 里最近一次成功 Monitor 的时间（epoch 秒），取不到输出 0
last_monitor_ok() {
    local v
    v=$(curl -fsS -m 15 "$BASE/api/health" 2>/dev/null | jq -r '.lastMonitorOk // empty' 2>/dev/null)
    [ -n "$v" ] && date -d "$v" +%s 2>/dev/null || echo 0
}

# 本机角色：ACTIVE（负责运行）/ STANDBY（另一台机器在运行，定时任务不跑）；取不到输出空
instance_role() {
    curl -fsS -m 15 "$BASE/api/instance" 2>/dev/null | jq -r '.role // empty' 2>/dev/null | head -c 20
}
