#!/bin/sh
# 开发端发布：把当前提交推到 staging，等 GitHub Actions 在 Windows 和 Linux 上都通过，再快进合并到 main。
# 任何一个平台失败就停下，不合并（main 只接收两个平台都通过的提交）。
set -e
SHA=$(git rev-parse HEAD)
REPO=leo14881-eng/TrendPilot
git push -q -f origin HEAD:staging
echo "已推到 staging：$SHA，等待 CI…"
i=0
while :; do
  sleep 15
  i=$((i + 1))
  runs=$(gh api "repos/$REPO/commits/$SHA/check-runs" --jq '.check_runs[]|[.name,.status,.conclusion]|@tsv' 2>/dev/null || true)
  n=$(printf '%s\n' "$runs" | grep -c 'test (' || true)
  pending=$(printf '%s\n' "$runs" | grep -c -e in_progress -e queued || true)
  if [ "$n" -ge 2 ] && [ "$pending" -eq 0 ]; then break; fi
  if [ "$i" -ge 120 ]; then echo "CI 30 分钟没有结果，停止"; exit 2; fi
done
printf '%s\n' "$runs"
if printf '%s\n' "$runs" | grep 'test (' | grep -vq 'success$'; then
  echo "CI 失败，不合并到 main"; exit 1
fi
git push -q origin "$SHA:main"
echo "两个平台都通过，已合并到 main：$SHA"
