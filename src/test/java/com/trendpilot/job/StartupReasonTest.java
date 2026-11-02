package com.trendpilot.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Weekly review R5: each start records the version and whether the previous run shut down normally. */
class StartupReasonTest {

    private static final Instant T = Instant.parse("2026-10-28T14:54:00Z");

    @Test
    void deployWithCleanShutdown() {
        assertThat(StartupCheck.reason("6c4e173", "c1720ec", T.minusSeconds(3600), T.minusSeconds(60)))
                .isEqualTo("version=c1720ec prev_version=6c4e173 reason=DEPLOY previous_exit=CLEAN");
    }

    @Test
    void sameVersionWithoutShutdownRecordIsAnUncleanRestart() {
        // started at T-1h, the last clean stop is older than that start: the process ended without a normal shutdown
        assertThat(StartupCheck.reason("c1720ec", "c1720ec", T.minusSeconds(3600), T.minusSeconds(7200)))
                .isEqualTo("version=c1720ec prev_version=c1720ec reason=RESTART previous_exit=UNCLEAN");
        assertThat(StartupCheck.reason("c1720ec", "c1720ec", T.minusSeconds(3600), null))
                .endsWith("reason=RESTART previous_exit=UNCLEAN");
    }

    @Test
    void firstStartIsUnknown() {
        assertThat(StartupCheck.reason(null, "c1720ec", null, null))
                .isEqualTo("version=c1720ec prev_version=? reason=FIRST_START previous_exit=UNKNOWN");
    }

    @Test
    void versionIsReadFromVersionFileAndShortened(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("version.txt");
        Files.writeString(f, "c1720ec0123456789abcdef\r\n");
        assertThat(StartupCheck.readVersion(f)).isEqualTo("c1720ec");
        assertThat(StartupCheck.readVersion(dir.resolve("missing.txt"))).isEqualTo("?");
    }
}
