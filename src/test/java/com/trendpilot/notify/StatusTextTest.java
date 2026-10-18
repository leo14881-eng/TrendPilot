package com.trendpilot.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.lease.ActiveLease;
import com.trendpilot.portfolio.Ledger;

class StatusTextTest {

    @Test
    @SuppressWarnings("unchecked")
    void missingPartsSayNotAvailableInsteadOfFailing(@TempDir Path dir) throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("数据库正忙"));
        StrategyParams params = mock(StrategyParams.class);
        when(params.version()).thenReturn("v3.4-test");
        ObjectProvider<Ledger> books = mock(ObjectProvider.class);
        when(books.orderedStream()).thenReturn(Stream.empty());
        ObjectProvider<ActiveLease> lease = mock(ObjectProvider.class);
        Path version = dir.resolve("version.txt");
        Files.writeString(version, "abcdef1234567890\n");

        String text = new StatusText(jdbc, params, books, lease, version).text(Instant.parse("2026-10-18T10:00:00Z"));

        assertThat(text).contains("程序版本：abcdef1").contains("策略版本：v3.4-test")
                .contains("最近一次持仓检查：" + StatusText.MISSING).contains("持仓数：" + StatusText.MISSING);
        String noVersion = new StatusText(jdbc, params, books, lease, dir.resolve("none.txt")).text(Instant.now());
        assertThat(noVersion).contains("程序版本：" + StatusText.MISSING);
    }
}
