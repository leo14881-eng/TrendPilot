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
}
