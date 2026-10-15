package com.trendpilot.notify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                state, report, mock(ObjectProvider.class), true, true);

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
}
