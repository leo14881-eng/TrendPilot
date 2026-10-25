#!/usr/bin/env bash
# 只读的硬盘排查（服务器桥动作 disk，用户 2026-10-26 要求排查硬盘写满）：
# 只看不改——不删除、不停服务、不安装软件；不输出配置内容、命令行参数和 crontab 内容（可能含密钥）。
set -uo pipefail
echo "== 主机 $(hostname)，$(date '+%F %T %Z')"
df -h / /tmp 2>/dev/null
echo
echo "== 30 秒内写盘最多的进程（按 systemd 单元，/proc/<pid>/io 的 write_bytes 差值）"
snap() { for d in /proc/[0-9]*; do
    w=$(awk '/^write_bytes/{print $2}' "$d/io" 2>/dev/null) || continue
    [ -n "$w" ] && echo "${d#/proc/} $w"; done; }
# 硬盘满时写不了临时文件：快照放在变量里
A=$(snap | sort); sleep 30; B=$(snap | sort)
join <(echo "$A") <(echo "$B") | awk '{d=$3-$2; if (d>1048576) print d, $1}' | sort -rn | head -10 | while read -r d pid; do
    unit=$(sed -n 's#.*/\([^/]*\.service\).*#\1#p' "/proc/$pid/cgroup" 2>/dev/null | head -1)
    awk -v d="$d" -v p="$pid" -v c="$(cat /proc/$pid/comm 2>/dev/null)" -v u="${unit:--}" 'BEGIN{printf "%8.1f MB  pid %s  %s  %s\n", d/1048576, p, c, u}'
done
echo
echo "== 30 秒内各目录增长（KB）"
dirs="/var/lib/stock-shadow /var/lib/private /opt/stock-shadow /opt/shadow-runner /opt/trendpilot /tmp /var/log"
A=$(du -xs $dirs 2>/dev/null); sleep 30; B=$(du -xs $dirs 2>/dev/null)
join -1 2 -2 2 <(echo "$A" | sort -k2) <(echo "$B" | sort -k2) | awk '{printf "%-28s %10d -> %10d  (%+d)\n", $1, $2, $3, $3-$2}'
echo
echo "== 最近 10 分钟修改过的大文件（> 50MB）"
find / -xdev -type f -mmin -10 -size +50M -printf '%s\t%p\n' 2>/dev/null | sort -n | tail -20
echo
echo "== 已删除但仍被进程占用的文件（> 10MB）"
lsof -nP +L1 2>/dev/null | awk 'NR==1 || $7>10000000 {print $1, $2, $7, $NF}' | head -20
echo
echo "== 目录明细"
du -xh /var/lib/private /tmp /opt --max-depth=2 2>/dev/null | sort -h | tail -30
echo
echo "== 相关服务和定时器"
systemctl list-units --type=service --state=running --no-pager 2>/dev/null | grep -i -E "shadow|stock|trend|monitor|runner"
systemctl list-timers --all --no-pager 2>/dev/null | grep -i -E "shadow|stock|monitor|runner|trend"
echo "cron.d：$(ls /etc/cron.d 2>/dev/null | tr '\n' ' ')"
journalctl --disk-usage 2>/dev/null
