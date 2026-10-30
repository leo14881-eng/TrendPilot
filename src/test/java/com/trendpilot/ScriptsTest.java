package com.trendpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** 自动更新脚本：git 输出必须按 UTF-8 读取，否则 Telegram 里的中文提交说明会变成乱码。 */
class ScriptsTest {

    @Test
    void autoUpdateReadsGitOutputAsUtf8() throws Exception {
        String s = Files.readString(Path.of("scripts/auto-update.ps1"), StandardCharsets.UTF_8);
        assertThat(s).contains("StandardOutputEncoding = New-Object System.Text.UTF8Encoding")
                .contains("i18n.logOutputEncoding=UTF-8").contains("core.quotePath=false");
        // 除了 clone（输出丢弃），不允许直接捕获 git.exe 的输出
        assertThat(s.lines().filter(l -> l.contains("& git.exe") && !l.trim().startsWith("#")))
                .allMatch(l -> l.contains("clone") && l.contains("Out-Null"));
    }

    @Test
    void exportActionIsReadOnlyAndSkipsSecrets() throws Exception {
        String ps = Files.readString(Path.of("scripts/auto-update.ps1"), StandardCharsets.UTF_8);
        assertThat(ps).contains("export   = \"$BootClone && bash /tmp/tp-boot/scripts/linux/export.sh\"")
                .contains("/opt/trendpilot/export/trendpilot-export.tar.gz");
        String sh = Files.readString(Path.of("scripts/linux/export.sh"), StandardCharsets.UTF_8);
        assertThat(sh).doesNotContain("\r").contains("sqlite3 -readonly");
        // 只读：不写数据库；不导配置、日志、Telegram 消息
        assertThat(sh.lines().filter(l -> !l.trim().startsWith("#")))
                .noneMatch(l -> l.matches("(?i).*\\b(INSERT|UPDATE|DELETE|DROP|ALTER|VACUUM)\\b.*"))
                .noneMatch(l -> l.contains("$CONFIG") || l.contains("config/") || l.contains("logs/")
                        || l.contains("notification_outbox"));
    }

    @Test
    void diskActionOnlyReads() throws Exception {
        String ps = Files.readString(Path.of("scripts/auto-update.ps1"), StandardCharsets.UTF_8);
        assertThat(ps).contains("disk     = 'bash /opt/trendpilot/scripts/linux/disk-report.sh'");
        String sh = Files.readString(Path.of("scripts/linux/disk-report.sh"), StandardCharsets.UTF_8);
        assertThat(sh).doesNotContain("\r");
        // 只看不改：不删除、不停服务、不安装、不输出 crontab 内容和命令行参数
        assertThat(sh.lines().filter(l -> !l.trim().startsWith("#")))
                .noneMatch(l -> l.matches(".*\\b(rm -rf|systemctl (stop|restart|disable)|apt-get|crontab -l|truncate)\\b.*"))
                .noneMatch(l -> l.contains("/cmdline") || l.contains("config/"));
    }

    @Test
    void serverAutoUpdateDoesNotTreatStandbyAsStuck() throws Exception {
        String common = Files.readString(Path.of("scripts/linux/common.sh"), StandardCharsets.UTF_8);
        assertThat(common).doesNotContain("\r").contains("instance_role()").contains("$BASE/api/instance");
        String sh = Files.readString(Path.of("scripts/linux/auto-update.sh"), StandardCharsets.UTF_8);
        assertThat(sh).doesNotContain("\r");
        // 待命（另一台机器在运行）时 Monitor 本来就不跑：守护不因此重启，更新不因此回滚
        String watchdog = sh.substring(sh.indexOf("watchdog() {"), sh.indexOf("# ---------- 2."));
        assertThat(watchdog).contains("[ \"$(instance_role)\" = STANDBY ] && return 0");
        assertThat(watchdog.indexOf("instance_role")).isLessThan(watchdog.indexOf("systemctl restart"));
        String wait = sh.substring(sh.indexOf("wait_monitor_ok() {"), sh.indexOf("# GitHub Actions"));
        assertThat(wait).contains("[ \"$(instance_role)\" = STANDBY ] && return 0");
    }

    @Test
    void fallbackStartsThePcOnlyAfterTheServerIsStoppedAndItsDatabaseIsBack() throws Exception {
        String ps = Files.readString(Path.of("scripts/auto-update.ps1"), StandardCharsets.UTF_8).replace("\r", "");
        // 服务器端：停服务后再确认确实不在运行，否则退出码非 0
        assertThat(ps).contains("systemctl stop trendpilot.service && ! systemctl is-active --quiet trendpilot.service");
        String fb = ps.substring(ps.indexOf("} elseif ($action -eq 'fallback') {"), ps.indexOf("} elseif ($action -eq 'export') {"));
        int stopCheck = fb.indexOf("$ServerScripts.fallback) -eq 0");
        int download = fb.indexOf("root@${ip}:/opt/trendpilot/data/$n");
        int clearWal = fb.indexOf("trendpilot.db-wal");
        int start = fb.indexOf("Start-TrendPilot");
        assertThat(stopCheck).isPositive();
        assertThat(download).isGreaterThan(stopCheck);
        assertThat(start).isGreaterThan(download);
        assertThat(clearWal).isGreaterThan(stopCheck).isLessThan(start);
        // 只有一处启动电脑服务，而且在"全部成功"的分支里；失败要发 Telegram 请人工处理
        assertThat(fb.split("Start-TrendPilot", -1)).hasSize(2);
        assertThat(fb).contains("if ($ok)").contains("需要人工处理");
        // 替换前先备份本机数据库
        assertThat(fb).contains("before-fallback");
    }

    @Test
    void statusSummaryTodayUsesTheVietnamDayLikeTheDailyReport() throws Exception {
        String sh = Files.readString(Path.of("scripts/linux/status-push.sh"), StandardCharsets.UTF_8);
        // "今天" = 越南时间 0 点起（与 V2 日报同一日界），换算成 UTC 再和库里的 UTC 时间比较；不依赖服务器时区
        assertThat(sh).contains("day_start=$(date -u -d \"$(TZ=Asia/Ho_Chi_Minh date +%F) 00:00 +07:00\" +%Y-%m-%dT%H:%M:%S)");
        assertThat(sh).contains("今天（越南时间）开仓").contains("今天（越南时间）成交笔数");
    }
}
