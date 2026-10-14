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
}
