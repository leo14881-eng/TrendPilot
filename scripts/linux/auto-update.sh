#!/usr/bin/env bash
# TrendPilot 服务器自动更新与守护（systemd 定时器每 3 分钟以 root 运行一次），对应电脑上的 auto-update.ps1 + watchdog.ps1：
#   1. 守护：配置和数据库都到位但服务没在跑 → 拉起；服务在跑但 Monitor 超过 20 分钟没成功（卡死）→ 重启并通知。
#      app/disabled.flag 存在时不拉起（迁移、维护时用）。
#   2. 更新：GitHub main 有新提交、且 Linux CI 已通过时：
#      只改文档 → 复制文件，不构建、不重启；否则构建（含全部测试），成功后在 Monitor / Discovery 的空档换 jar 重启；
#      启动后等到一次成功的 Monitor 才算成功，否则回滚到上一版（2 小时后再试这个版本）。构建失败继续跑旧版，同一提交只提醒一次。
# 从不改动：config/application-local.yml、data、logs。
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=common.sh
. "$HERE/common.sh"

exec 9>/run/trendpilot-update.lock
flock -n 9 || exit 0

git_src() { sudo -u "$RUN_USER" -H git -C "$SRC_DIR" "$@"; }

service_ready() { [ -f "$CONFIG" ] && [ -f "$DB" ] && [ ! -f "$BIN/disabled.flag" ]; }

start_service() {
    systemctl start trendpilot.service
    for _ in $(seq 1 30); do
        sleep 5
        healthy && return 0
    done
    return 1
}

# 等到空档：Monitor 每 5 分钟整点跑（:00 :05 ...），Discovery 在 :17。停在 Monitor 之后 2~3 分钟、避开 :15~:21。
wait_quiet_window() {
    for _ in $(seq 1 48); do
        local m=$((10#$(date +%M)))
        if { [ $((m % 5)) -eq 2 ] || [ $((m % 5)) -eq 3 ]; } && { [ "$m" -lt 15 ] || [ "$m" -gt 21 ]; }; then return; fi
        sleep 10
    done
}

# 新版本启动后，等到一次成功的 Monitor（最多约 11 分钟）；本机待命时 Monitor 不跑，服务正常响应就算启动成功
wait_monitor_ok() {
    local since=$1
    for _ in $(seq 1 132); do
        sleep 5
        [ "$(last_monitor_ok)" -ge "$since" ] && return 0
        [ "$(instance_role)" = STANDBY ] && return 0
    done
    return 1
}

# GitHub Actions 在 Linux 上的测试结果：success / failure / pending / unknown
ci_state() {
    local json
    json=$(curl -fsS -m 20 -H 'Accept: application/vnd.github+json' -H 'User-Agent: TrendPilot-AutoUpdate' \
        "https://api.github.com/repos/leo14881-eng/TrendPilot/commits/$1/check-runs" 2>/dev/null) || { echo unknown; return; }
    echo "$json" | jq -r '[.check_runs[] | select(.name | test("ubuntu"))] |
        if length == 0 then "unknown"
        elif any(.status != "completed") then "pending"
        elif any(.conclusion != "success") then "failure"
        else "success" end' 2>/dev/null || echo unknown
}

# ---------- 1. 守护 ----------
watchdog() {
    service_ready || return 0
    if ! systemctl is-active --quiet trendpilot.service; then
        log "服务没在运行，拉起"
        if start_service; then log "已拉起"; else send_telegram "⚠️ 服务器上的服务没能启动，稍后自动重试"; fi
        return
    fi
    healthy || return 0   # 刚启动或短暂无响应：下一轮再看
    # 待命（另一台机器在运行）时 Monitor 本来就不跑，不能当成卡死
    [ "$(instance_role)" = STANDBY ] && return 0
    local ok now started
    ok=$(last_monitor_ok)
    now=$(date +%s)
    started=$(date -d "$(systemctl show -p ActiveEnterTimestamp --value trendpilot.service)" +%s 2>/dev/null || echo "$now")
    if [ $((now - started)) -gt 1500 ] && [ $((now - ok)) -gt 1200 ]; then
        log "Monitor 超过 20 分钟没有成功，重启服务"
        systemctl restart trendpilot.service
        send_telegram "⚠️ 服务器上 Monitor 超过 20 分钟没有运行，已重启服务"
    fi
}

# ---------- 2. 更新 ----------
update() {
    git_src fetch --quiet origin "$BRANCH" 2>>"$LOG_FILE" || { log "git fetch 失败"; return; }
    local latest current short subject
    latest=$(git_src rev-parse "origin/$BRANCH")
    current=$(read_text "$VERSION_FILE")
    [ "$latest" = "$current" ] && return
    [ "$latest" = "$(read_text "$FAILED_FILE")" ] && return
    if [ "$latest" = "$(read_text "$MON_FAILED_FILE")" ] && [ -n "$(find "$MON_FAILED_FILE" -mmin -120 2>/dev/null)" ]; then return; fi
    short=${latest:0:7}
    subject=$(git_src log -1 --format=%s "$latest")
    case "$(ci_state "$latest")" in
        success) ;;
        failure) log "$short 的 Linux CI 没通过，不安装"; echo "$latest" > "$FAILED_FILE"; return ;;
        *) log "等待 $short 的 Linux CI 结果"; return ;;
    esac
    log "发现新提交 ${short}：$subject"

    local changed docs_only=0
    if [ -n "$current" ]; then
        changed=$(git_src diff --name-only "$current" "$latest" 2>/dev/null || true)
        if [ -n "$changed" ] && ! echo "$changed" | grep -qvE '^docs/|\.md$'; then docs_only=1; fi
    fi
    git_src checkout --quiet --force "$latest"
    if [ "$docs_only" -eq 1 ]; then
        cp -r "$SRC_DIR/docs" "$APP_DIR/" 2>/dev/null
        echo "$latest" > "$VERSION_FILE"
        log "只改了文档，已更新到 $short（不重启）"
        return
    fi

    if ! sudo -u "$RUN_USER" -H bash -c "cd '$SRC_DIR' && sh ./mvnw -B -q clean package" > "$APP_DIR/logs/build.log" 2>&1; then
        log "$short 构建失败，继续运行旧版本"
        echo "$latest" > "$FAILED_FILE"
        send_telegram "⚠️ 服务器上 $short 构建没通过，继续运行旧版本（$subject）"
        return
    fi

    local scripts_changed=0
    if ! diff -rq "$SRC_DIR/scripts/linux" "$APP_DIR/scripts/linux" >/dev/null 2>&1; then scripts_changed=1; fi

    if service_ready && systemctl is-active --quiet trendpilot.service; then wait_quiet_window; fi
    systemctl stop trendpilot.service
    [ -f "$JAR" ] && cp -f "$JAR" "$PREV_JAR"
    install -o "$RUN_USER" -g "$RUN_USER" -m 644 "$SRC_DIR/target/trendpilot.jar" "$JAR"
    cp -r "$SRC_DIR/scripts/." "$APP_DIR/scripts/"
    cp -r "$SRC_DIR/docs" "$APP_DIR/" 2>/dev/null
    [ "$scripts_changed" -eq 1 ] && bash "$APP_DIR/scripts/linux/install.sh" --units

    if ! service_ready; then
        echo "$latest" > "$VERSION_FILE"
        log "已更新到 $short（配置或数据库未到位，服务未启动）"
        return
    fi
    local since
    since=$(date +%s)
    if start_service && wait_monitor_ok "$since"; then
        echo "$latest" > "$VERSION_FILE"
        log "已更新到 $short"
    else
        log "$short 启动后没有成功的 Monitor，回滚"
        systemctl stop trendpilot.service
        [ -f "$PREV_JAR" ] && cp -f "$PREV_JAR" "$JAR"
        echo "$latest" > "$MON_FAILED_FILE"
        start_service || true
        send_telegram "⚠️ 服务器上 $short 启动后 Monitor 没有成功，已回滚到上一版，2 小时后再试"
    fi
}

watchdog
update
