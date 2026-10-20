#!/usr/bin/env bash
# TrendPilot 服务器一次性安装（Ubuntu 22.04 / 24.04，root 运行，可重复运行）。
#   1. 先检查这台服务器能不能访问 Binance、Bybit 和 Telegram 的公开接口；交易所不通就停下，不安装。
#   2. 安装 Java 21、git、jq、sqlite3；时区设为越南时间（日志和电脑一致）；内存小于 6G 时加 2G 交换文件。
#   3. 建普通用户 trendpilot 运行服务；源码在 /opt/trendpilot-src，运行目录 /opt/trendpilot。
#   4. 构建（含全部测试），注册 systemd：
#        trendpilot.service        服务本身（崩溃 30 秒后自动拉起）。只有配置和数据库都到位才启动，
#                                  避免空数据库抢走 Telegram 租约、和电脑上的账本分叉。
#        trendpilot-update.timer   每 3 分钟 auto-update.sh：自动更新 + 卡死检测。
# 用法：bash install.sh            完整安装
#       bash install.sh --check    只做网络检查
#       bash install.sh --units    只重新写 systemd 单元（自动更新在脚本变化时调用）
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=common.sh
. "$HERE/common.sh"

[ "$(id -u)" -eq 0 ] || { echo "请用 root 运行"; exit 1; }

check_network() {
    local fail=0 code
    for item in \
        "Binance|https://api.binance.com/api/v3/exchangeInfo?symbol=BTCUSDT" \
        "Binance 行情|https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT" \
        "Bybit|https://api.bybit.com/v5/market/tickers?category=spot&symbol=BTCUSDT" \
        "Telegram|https://api.telegram.org/" \
        "GitHub|https://api.github.com/repos/leo14881-eng/TrendPilot"; do
        local name=${item%%|*} url=${item#*|}
        code=$(curl -s -m 20 -o /tmp/tp-net-check -w '%{http_code}' "$url" || echo 000)
        case "$name" in
            Telegram) [ "$code" != 000 ] && echo "OK   $name（HTTP $code）" || { echo "FAIL $name 连不上"; fail=1; } ;;
            Bybit)
                if [ "$code" = 200 ] && grep -q '"retCode":0' /tmp/tp-net-check; then echo "OK   $name"
                else echo "FAIL $name（HTTP $code）：这个地区可能被 Bybit 限制"; fail=1; fi ;;
            *)  [ "$code" = 200 ] && echo "OK   $name" || { echo "FAIL $name（HTTP $code）：这个地区可能被限制"; fail=1; } ;;
        esac
    done
    rm -f /tmp/tp-net-check
    return $fail
}

write_units() {
    cat > /etc/systemd/system/trendpilot.service <<EOF
[Unit]
Description=TrendPilot shadow trading service
After=network-online.target
Wants=network-online.target
ConditionPathExists=$CONFIG
ConditionPathExists=$DB
ConditionPathExists=!$BIN/disabled.flag

[Service]
User=$RUN_USER
WorkingDirectory=$APP_DIR
# 内存（2026-10-20 实测：完整 GC 后堆约 23MB，进程常驻约 290MB，大部分是类元数据、编译代码和本机内存）：
# 堆上限 768MB；小堆用 Serial GC 省去 G1 的额外本机内存；限制元空间和代码缓存；
# glibc 内存池限 2 个（线程多时默认会开很多池，常驻内存虚高）；真的内存不足时直接退出，由 systemd 30 秒后重启
Environment=MALLOC_ARENA_MAX=2
ExecStart=/usr/bin/java -Xmx768m -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m -XX:+ExitOnOutOfMemoryError -jar $JAR
Restart=always
RestartSec=30
StandardOutput=append:$APP_DIR/logs/console.log
StandardError=append:$APP_DIR/logs/console.log

[Install]
WantedBy=multi-user.target
EOF
    cat > /etc/systemd/system/trendpilot-update.service <<EOF
[Unit]
Description=TrendPilot auto update and watchdog

[Service]
Type=oneshot
ExecStart=/bin/bash $APP_DIR/scripts/linux/auto-update.sh
TimeoutStartSec=3600
EOF
    cat > /etc/systemd/system/trendpilot-update.timer <<EOF
[Unit]
Description=Run TrendPilot auto update every 3 minutes

[Timer]
OnBootSec=2min
OnUnitInactiveSec=3min

[Install]
WantedBy=timers.target
EOF
    systemctl daemon-reload
    systemctl enable trendpilot.service trendpilot-update.timer >/dev/null 2>&1
    systemctl start trendpilot-update.timer
}

case "${1:-}" in
    --check) check_network; exit $? ;;
    --units) write_units; exit 0 ;;
esac

echo "== 1/5 网络检查"
if ! check_network; then
    echo "交易所或 Telegram 访问不通，停止安装。可以删掉这台服务器，换一个地区（推荐东京）重建。"
    exit 2
fi

echo "== 2/5 安装 Java 21 等工具"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq openjdk-21-jdk-headless git jq sqlite3 curl ca-certificates >/dev/null
timedatectl set-timezone Asia/Ho_Chi_Minh || true
mem_kb=$(awk '/MemTotal/{print $2}' /proc/meminfo)
if [ "$mem_kb" -lt 6000000 ] && ! swapon --show | grep -q /swapfile; then
    fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== 3/5 用户和目录"
id "$RUN_USER" >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin "$RUN_USER"
mkdir -p "$BIN" "$APP_DIR/config" "$APP_DIR/data" "$APP_DIR/logs" "$APP_DIR/scripts"
mkdir -p "$SRC_DIR" && chown "$RUN_USER:$RUN_USER" "$SRC_DIR"
if [ ! -d "$SRC_DIR/.git" ]; then
    sudo -u "$RUN_USER" -H git clone --quiet --branch "$BRANCH" "$REPO_URL" "$SRC_DIR"
else
    sudo -u "$RUN_USER" -H git -C "$SRC_DIR" fetch --quiet origin "$BRANCH"
    sudo -u "$RUN_USER" -H git -C "$SRC_DIR" checkout --quiet --force "origin/$BRANCH"
fi
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR"
chmod 700 "$APP_DIR/config"

echo "== 4/5 构建（含全部测试，约 5～10 分钟）"
sudo -u "$RUN_USER" -H bash -c "cd '$SRC_DIR' && sh ./mvnw -B -q clean package" > "$APP_DIR/logs/build.log" 2>&1 \
    || { echo "构建失败，见 $APP_DIR/logs/build.log"; exit 3; }
install -o "$RUN_USER" -g "$RUN_USER" -m 644 "$SRC_DIR/target/trendpilot.jar" "$JAR"
cp -r "$SRC_DIR/scripts/." "$APP_DIR/scripts/"
cp -r "$SRC_DIR/docs" "$APP_DIR/" 2>/dev/null || true
sudo -u "$RUN_USER" -H git -C "$SRC_DIR" rev-parse HEAD > "$VERSION_FILE"
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR"

echo "== 5/5 注册 systemd"
write_units
log "安装完成：$(cut -c1-7 "$VERSION_FILE")"
if [ -f "$CONFIG" ] && [ -f "$DB" ]; then
    systemctl restart trendpilot.service
    echo "安装完成，服务已启动。"
else
    echo "安装完成。服务会在配置（$CONFIG）和数据库（$DB）都到位后启动。"
fi
