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
}
