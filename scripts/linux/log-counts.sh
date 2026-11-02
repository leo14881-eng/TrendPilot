#!/usr/bin/env bash
# 最近 N 天（默认 7）WARN / ERROR 日志按小时、级别和 logger 名计数，输出 CSV：hour,level,logger,count。
# 只输出计数和 logger 名，从不输出日志原文（消息里可能有接口地址、币名以外的内容）。被 export.sh 调用。
# 用法：log-counts.sh <日志目录> [天数]
set -uo pipefail
DIR=${1:?日志目录}
DAYS=${2:-7}
SINCE=$(date -d "-$DAYS days" '+%Y-%m-%dT%H')
echo "hour,level,logger,count"
# 当前日志 + 滚动归档（trendpilot.log.YYYY-MM-DD.N.gz）；zcat -f 同时读普通文件和 .gz
find "$DIR" -maxdepth 1 -type f -name 'trendpilot.log*' -print0 2>/dev/null | xargs -0 -r zcat -f 2>/dev/null \
    | awk -v s="$SINCE" '
        $1 ~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]/ && ($2 ~ /^(WARN|ERROR)$/) {
            hour = substr($1, 1, 13)
            if (hour < s) next
            logger = "?"
            # Spring Boot 格式：时间 级别 pid --- [应用] [线程] logger名 : 消息；只取 " : " 前面的最后一个词
            i = index($0, " : ")
            if (i > 0) { pre = substr($0, 1, i - 1); n = split(pre, w, " "); logger = w[n] }
            gsub(/[^A-Za-z0-9_.$-]/, "", logger)
            c[hour "," $2 "," logger]++
        }
        END { for (k in c) { split(k, f, ","); hour = f[1]; lvl = f[2]; logger = f[3]; n = c[k]; print hour "," lvl "," logger "," n } }' \
    | sort
