#!/usr/bin/env bash
# 每天把只读导出推到用户自己的私有仓库 leo14881-eng/trendpilot-data（用户 2026-11-02 同意：复盘不再依赖电脑）。
# systemd 定时器 trendpilot-data.timer：每天越南 06:13 一次，开机后 10 分钟一次。
#   - 内容 = export.sh 的只读导出（不含 config、密钥、日志原文、notification_outbox），
#     exports/YYYY-MM-DD/trendpilot-export.tar.gz；latest/ 是最新一份的副本（同一个 git 对象），latest.txt 写最新日期。
#   - 只保留最近 30 天：更早的日期目录删掉并提交。每次一个提交，正常推送 main。
#   - 体积：删掉的导出仍在 git 历史里。提交后若本地 .git 超过 max(300MB, 1.5 × 当前保留内容)，
#     把 main 重建为只有当前内容的孤儿提交、清掉本地旧对象，这一次用 --force-with-lease 推送（仓库只由这台服务器写入）。
#   - 用服务器自己生成的 deploy key（ssh/data_ed25519，私钥不离开服务器）。没有 key 就什么都不做。
#   - 任何失败只记日志和状态文件（logs/data-push.status，status-push.sh 显示），不影响服务。
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=common.sh
. "$HERE/common.sh"

KEY="$APP_DIR/ssh/data_ed25519"
REPO_DIR="$APP_DIR/data-repo"
REMOTE=git@github.com:leo14881-eng/trendpilot-data.git
STATUS="$APP_DIR/logs/data-push.status"
PUSH_LOG="$APP_DIR/logs/data-push.log"
ARCHIVE="$APP_DIR/export/trendpilot-export.tar.gz"
KEEP_DAYS=30
MAX_GIT_MB=300
[ -f "$KEY" ] || exit 0
mkdir -p "$APP_DIR/logs"

status() { echo "$(date '+%F %T %Z') $*" > "$STATUS"; log "数据仓库推送：$*"; }
as_user() { sudo -u "$RUN_USER" -H env GIT_SSH_COMMAND="ssh -i $KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$APP_DIR/ssh/known_hosts" "$@"; }
g() { as_user git -C "$REPO_DIR" "$@"; }

if ! bash "$HERE/export.sh" >>"$PUSH_LOG" 2>&1 || [ ! -s "$ARCHIVE" ]; then
    status "FAIL 导出失败"
    exit 0
fi
size=$(du -h "$ARCHIVE" | cut -f1)

if [ ! -d "$REPO_DIR/.git" ]; then
    mkdir -p "$REPO_DIR" && chown "$RUN_USER:$RUN_USER" "$REPO_DIR"
    g init -q
    g remote add origin "$REMOTE"
    g config user.name "TrendPilot server"
    g config user.email "server@trendpilot.invalid"
    if as_user timeout 300 git -C "$REPO_DIR" fetch -q --depth 1 origin main 2>>"$PUSH_LOG"; then
        g checkout -q -B main FETCH_HEAD
    else
        g checkout -q --orphan main
    fi
fi

day=$(TZ=Asia/Ho_Chi_Minh date +%F)
mkdir -p "$REPO_DIR/exports/$day" "$REPO_DIR/latest"
cp "$ARCHIVE" "$REPO_DIR/exports/$day/trendpilot-export.tar.gz"
cp "$ARCHIVE" "$REPO_DIR/latest/trendpilot-export.tar.gz"
echo "$day" > "$REPO_DIR/latest.txt"
chown -R "$RUN_USER:$RUN_USER" "$REPO_DIR/exports" "$REPO_DIR/latest" "$REPO_DIR/latest.txt"
cutoff=$(TZ=Asia/Ho_Chi_Minh date -d "-$KEEP_DAYS days" +%F)
for d in "$REPO_DIR"/exports/*/; do
    [ -d "$d" ] || continue
    name=$(basename "$d")
    if [[ "$name" < "$cutoff" ]]; then
        g rm -r -q --cached --ignore-unmatch "exports/$name"
        rm -rf "$d"
    fi
done
g add -A exports latest latest.txt
g commit -q -m "data export $day ($size)" || true

force=()
git_mb=$(du -sm "$REPO_DIR/.git" | cut -f1)
tree_mb=$(du -sm "$REPO_DIR/exports" | cut -f1)
limit=$(( tree_mb * 3 / 2 > MAX_GIT_MB ? tree_mb * 3 / 2 : MAX_GIT_MB ))
if [ "$git_mb" -gt "$limit" ]; then
    g checkout -q --orphan rebuild
    g add -A exports latest latest.txt
    g commit -q -m "data export $day ($size, history rebuilt: .git ${git_mb}MB > ${limit}MB)"
    g branch -q -D main
    g branch -q -m main
    g reflog expire --expire=now --all
    g gc -q --prune=now
    force=(--force-with-lease)
    log "数据仓库历史已重建（.git ${git_mb}MB 超过 ${limit}MB）"
fi
if as_user timeout 900 git -C "$REPO_DIR" push -q "${force[@]}" origin main 2>>"$PUSH_LOG"; then
    status "OK $day $size"
else
    status "FAIL 推送失败（见 logs/data-push.log）"
fi
exit 0
