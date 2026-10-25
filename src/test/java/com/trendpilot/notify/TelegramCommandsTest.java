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
        when(report.telegramText(any(Instant.class))).thenReturn("📊 TrendPilot V2 日报 2026-10-15\n【二、持仓明细】\nABC · BINANCE");
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
    void rollbackByCommandOrPlainWordRestoresPreviousParameterSet() {
        Notifier notifier = mock(Notifier.class);
        TelegramCommands c = new TelegramCommands(new TelegramProperties("1:t", "42", null), new ObjectMapper(), notifier,
                mock(AppState.class), mock(HoldingsReport.class), mock(StatusText.class), mock(ObjectProvider.class), true, true);
        com.trendpilot.variant.ParamSets ps = mock(com.trendpilot.variant.ParamSets.class);
        ObjectProvider<com.trendpilot.variant.ParamSets> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ps);
        c.setParamSets(provider);
        Instant now = Instant.parse("2026-11-23T01:00:00Z");
        when(ps.rollback(any(Instant.class)))
                .thenReturn(java.util.Optional.of(new com.trendpilot.variant.ParamSets.Switch(1, "PROMOTE", "V6", "", "max-volume-accel=4", now, false)))
                .thenReturn(java.util.Optional.empty());
        c.handle("""
                {"ok":true,"result":[
                 {"update_id":20,"message":{"chat":{"id":42},"text":"回滚"}},
                 {"update_id":21,"message":{"chat":{"id":42},"text":"/rollback"}}
                ]}""", now);
        verify(notifier).send(eq("cmd:20"), contains("已回滚：撤销 V6"));
        verify(notifier).send(eq("cmd:21"), contains("没有自动转正过的参数集"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedCommandIsRetriedNotSkippedUpToFiveTimes() {
        Notifier notifier = mock(Notifier.class);
        AppState state = mock(AppState.class);
        HoldingsReport report = mock(HoldingsReport.class);
        when(report.telegramText(any(Instant.class))).thenThrow(new IllegalStateException("Failed to obtain JDBC Connection"))
                .thenReturn("📊 TrendPilot V2 日报");
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
        when(report.telegramText(any(Instant.class))).thenThrow(new IllegalStateException("坏了"));
        String bad = """
                {"ok":true,"result":[{"update_id":30,"message":{"chat":{"id":42},"text":"/v2"}}]}""";
        for (int i = 0; i < 4; i++) assertThatThrownBy(() -> c.handle(bad, now));
        c.handle(bad, now);
        assertThat(c.offset()).isEqualTo(31L);
        verify(report, times(7)).telegramText(any(Instant.class));
    }
    @Test
    @SuppressWarnings("unchecked")
    void holdingsAndStatusCommands() {
        Notifier notifier = mock(Notifier.class);
        HoldingsReport report = mock(HoldingsReport.class);
        StatusText status = mock(StatusText.class);
        when(report.telegramText(any(Instant.class))).thenReturn("📊 TrendPilot V2 日报 2026-10-18");
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
        when(report.telegramText(any(Instant.class))).thenThrow(new IllegalStateException("数据库正忙"));
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

    @Test
    void helpTextAndMenuComeFromTheSameDefinition() {
        for (TelegramCommands.Cmd c : TelegramCommands.MENU) {
            assertThat(TelegramCommands.HELP).contains("/" + c.name() + " " + c.description());
            assertThat(c.name()).matches("[a-z0-9_]{1,32}"); // Telegram 对指令名的要求
            assertThat(c.description()).isNotBlank().hasSizeLessThanOrEqualTo(256);
        }
        assertThat(TelegramCommands.MENU).extracting(TelegramCommands.Cmd::name)
                .containsExactly("holdings", "v2", "status", "trend", "rollback", "help");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, String>> cmds =
                (java.util.List<java.util.Map<String, String>>) TelegramCommands.menuRequest().get("commands");
        assertThat(cmds).hasSize(6);
        assertThat(cmds.get(0)).containsEntry("command", "holdings").containsEntry("description", "当前持仓、当天盈亏和资金情况");
    }

    @Test
    @SuppressWarnings("unchecked")
    void menuIsRegisteredOnceAndSkippedWhenUnchanged() {
        AppState state = mock(AppState.class);
        when(state.get(TelegramCommands.MENU_KEY)).thenReturn(java.util.Optional.empty());
        TelegramCommands c = new TelegramCommands(new TelegramProperties("1:t", "42", null), new ObjectMapper(), mock(Notifier.class),
                state, mock(HoldingsReport.class), mock(StatusText.class), mock(ObjectProvider.class), true, true);
        org.springframework.web.client.RestClient.Builder b = org.springframework.web.client.RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(b).build();
        c.useClient(b.baseUrl("https://tg.test").build());

        // 第一次 429：不抛出，按 retry_after 等
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.endsWith("/setMyCommands")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"parameters\":{\"retry_after\":120}}"));
        c.registerMenuIfDue(1_000);
        c.registerMenuIfDue(60_000); // 还没到 120 秒，不请求
        server.verify();
        server.reset();

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.endsWith("/setMyCommands")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.commands[0].command").value("holdings"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.commands[2].command").value("status"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"ok\":true,\"result\":true}", org.springframework.http.MediaType.APPLICATION_JSON));
        c.registerMenuIfDue(130_000);
        server.verify();
        verify(state).put(eq(TelegramCommands.MENU_KEY), anyString());
        c.registerMenuIfDue(999_999); // 注册过了，不再请求
        server.verify();

        // 新实例：记录和当前菜单一样就跳过，不发请求
        org.mockito.ArgumentCaptor<String> fp = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(state).put(eq(TelegramCommands.MENU_KEY), fp.capture());
        AppState state2 = mock(AppState.class);
        when(state2.get(TelegramCommands.MENU_KEY)).thenReturn(java.util.Optional.of(fp.getValue()));
        TelegramCommands c2 = new TelegramCommands(new TelegramProperties("1:t", "42", null), new ObjectMapper(), mock(Notifier.class),
                state2, mock(HoldingsReport.class), mock(StatusText.class), mock(ObjectProvider.class), true, true);
        server.reset();
        c2.useClient(b.baseUrl("https://tg.test").build());
        c2.registerMenuIfDue(1);
        server.verify();
    }
}
