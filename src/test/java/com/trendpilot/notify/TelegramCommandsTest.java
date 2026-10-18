package com.trendpilot.notify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendpilot.job.AppState;
import com.trendpilot.lease.ActiveLease;
import com.trendpilot.report.HoldingsReport;

class TelegramCommandsTest {

    @Test
    @SuppressWarnings("unchecked")
    void repliesToV2OnlyFromOwnChatAndRemembersOffset() {
        Notifier notifier = mock(Notifier.class);
        AppState state = mock(AppState.class);
        HoldingsReport report = mock(HoldingsReport.class);
        when(report.markdown(any(Instant.class))).thenReturn("# TrendPilot V2 日报 2026-10-15\n## 二、持仓明细\n| ABC | BINANCE |");
        TelegramCommands c = new TelegramCommands(new TelegramProperties("1:t", "42", null), new ObjectMapper(), notifier,
                state, report, mock(StatusText.class), mock(ObjectProvider.class), true, true);

        c.handle("""
                {"ok":true,"result":[
                 {"update_id":10,"message":{"chat":{"id":42},"text":"/v2"}},
                 {"update_id":11,"message":{"chat":{"id":999},"text":"/v2"}},
                 {"update_id":12,"message":{"chat":{"id":42},"text":"/help"}},
                 {"update_id":13,"message":{"chat":{"id":42},"text":"随便聊聊"}}
                ]}""", Instant.parse("2026-10-15T01:00:00Z"));

        verify(notifier).send(eq("cmd:10"), contains("📊 TrendPilot V2 日报"));
        verify(notifier).send(eq("cmd:12"), contains("/v2"));
        verify(notifier, never()).send(eq("cmd:11"), anyString()); // 别人的聊天不回应
        verify(notifier, never()).send(eq("cmd:13"), anyString()); // 普通聊天不回应
        verify(state).put(TelegramCommands.OFFSET_KEY, "14");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedCommandIsRetriedNotSkippedUpToFiveTimes() {
        Notifier notifier = mock(Notifier.class);
        AppState state = mock(AppState.class);
        HoldingsReport report = mock(HoldingsReport.class);
        when(report.markdown(any(Instant.class))).thenThrow(new IllegalStateException("Failed to obtain JDBC Connection"))
                .thenReturn("# TrendPilot V2 日报");
        TelegramCommands c = new TelegramCommands(new TelegramProperties("1:t", "42", null), new ObjectMapper(), notifier,
                state, report, mock(StatusText.class), mock(ObjectProvider.class), true, true);
        String batch = """
                {"ok":true,"result":[{"update_id":20,"message":{"chat":{"id":42},"text":"/V2"}}]}""";
        Instant now = Instant.parse("2026-10-15T01:00:00Z");

        assertThatThrownBy(() -> c.handle(batch, now)).hasMessageContaining("JDBC");
        assertThat(c.offset()).isNull(); // 编号没推进，下次还会读到这条
        c.handle(batch, now);
        verify(notifier).send(eq("cmd:20"), contains("V2 日报"));
        assertThat(c.offset()).isEqualTo(21L);

        // 一直失败的指令：第 5 次放弃，不卡住后面的指令
        when(report.markdown(any(Instant.class))).thenThrow(new IllegalStateException("坏了"));
        String bad = """
                {"ok":true,"result":[{"update_id":30,"message":{"chat":{"id":42},"text":"/v2"}}]}""";
        for (int i = 0; i < 4; i++) assertThatThrownBy(() -> c.handle(bad, now));
        c.handle(bad, now);
        assertThat(c.offset()).isEqualTo(31L);
        verify(report, times(7)).markdown(any(Instant.class));
    }
    @Test
    @SuppressWarnings("unchecked")
    void holdingsAndStatusCommands() {
        Notifier notifier = mock(Notifier.class);
        HoldingsReport report = mock(HoldingsReport.class);
        StatusText status = mock(StatusText.class);
        when(report.markdown(any(Instant.class))).thenReturn("# TrendPilot V2 日报 2026-10-18");
        when(status.text(any(Instant.class))).thenReturn("📟 运行状态\n持仓数：V2 0");
        TelegramCommands c = new TelegramCommands(new TelegramProperties("1:t", "42", null), new ObjectMapper(), notifier,
                mock(AppState.class), report, status, mock(ObjectProvider.class), true, true);

        c.handle("""
                {"ok":true,"result":[
                 {"update_id":40,"message":{"chat":{"id":42},"text":"/holdings"}},
                 {"update_id":41,"message":{"chat":{"id":42},"text":"/status@TrendPilotBot"}},
                 {"update_id":42,"message":{"chat":{"id":7},"text":"/status"}},
                 {"update_id":43,"message":{"chat":{"id":42},"text":"/foo"}}
                ]}""", Instant.parse("2026-10-18T10:00:00Z"));

        verify(notifier).send(eq("cmd:40"), contains("V2 日报"));
        verify(notifier).send(eq("cmd:41"), contains("运行状态"));
        verify(notifier, never()).send(eq("cmd:42"), anyString());
        verify(notifier).send(eq("cmd:43"), contains("/holdings"));
        assertThat(c.offset()).isEqualTo(44L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void standbyInstanceDoesNotReadCommands() {
        ObjectProvider<ActiveLease> provider = mock(ObjectProvider.class);
        ActiveLease lease = mock(ActiveLease.class);
        when(provider.getIfAvailable()).thenReturn(lease);
        TelegramCommands c = new TelegramCommands(new TelegramProperties("1:t", "42", null), new ObjectMapper(),
                mock(Notifier.class), mock(AppState.class), mock(HoldingsReport.class), mock(StatusText.class), provider, true, true);
        when(lease.active()).thenReturn(false);
        assertThat(c.shouldPoll()).isFalse();
        when(lease.active()).thenReturn(true);
        assertThat(c.shouldPoll()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void repliesNotAvailableWhenCommandKeepsFailing() {
        Notifier notifier = mock(Notifier.class);
        HoldingsReport report = mock(HoldingsReport.class);
        when(report.markdown(any(Instant.class))).thenThrow(new IllegalStateException("数据库正忙"));
        TelegramCommands c = new TelegramCommands(new TelegramProperties("1:t", "42", null), new ObjectMapper(), notifier,
                mock(AppState.class), report, mock(StatusText.class), mock(ObjectProvider.class), true, true);
        String bad = """
                {"ok":true,"result":[{"update_id":50,"message":{"chat":{"id":42},"text":"/holdings"}}]}""";
        Instant now = Instant.parse("2026-10-18T10:00:00Z");
        for (int i = 0; i < 4; i++) assertThatThrownBy(() -> c.handle(bad, now));
        c.handle(bad, now);
        verify(notifier).send(eq("cmd:50"), contains("暂时取不到"));
        assertThat(c.offset()).isEqualTo(51L);
    }
}
