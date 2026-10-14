package com.trendpilot.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.trendpilot.notify.Notifier;

class ActiveLeaseTest {

    /** 模拟 Telegram 私聊的置顶：pinned_message 是发送时间最新的那条置顶。 */
    static final class FakeChat implements ActiveLease.Board {
        record Msg(long id, String text, Instant sent, Instant edited) {
        }

        final List<Msg> msgs = new ArrayList<>();
        final List<Long> pins = new ArrayList<>();
        Instant clock = Instant.parse("2026-10-15T00:00:00Z");
        boolean down;

        @Override
        public Optional<ActiveLease.Pin> pinned() {
            if (down) throw new IllegalStateException("network");
            return msgs.stream().filter(m -> pins.contains(m.id())).max((a, b) -> a.sent().compareTo(b.sent()))
                    .map(m -> new ActiveLease.Pin(m.id(), m.text(), m.edited()));
        }

        @Override
        public long post(String text) {
            clock = clock.plusSeconds(1);
            long id = msgs.size() + 1;
            msgs.add(new Msg(id, text, clock, clock));
            return id;
        }

        @Override
        public void pin(long id) {
            pins.add(id);
        }

        @Override
        public void unpin(long id) {
            pins.remove(id);
        }

        @Override
        public void edit(long id, String text) {
            Msg m = msgs.get((int) id - 1);
            msgs.set((int) id - 1, new Msg(id, text, m.sent(), clock));
        }
    }

    private static ActiveLease lease(FakeChat chat, String name) {
        return new ActiveLease(chat, mock(Notifier.class), name, Duration.ofMinutes(10), 120, 0, ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    @Test
    void secondMachineWaitsAndTakesOverAfterSilence() {
        FakeChat chat = new FakeChat();
        ActiveLease a = lease(chat, "PC-A");
        ActiveLease b = lease(chat, "PC-B");
        a.tick(chat.clock);
        assertThat(a.active()).isTrue();

        chat.clock = chat.clock.plusSeconds(60);
        b.tick(chat.clock);
        assertThat(b.active()).isFalse();
        assertThat(b.owner()).contains("PC-A");

        // A 一直心跳，B 一直待命
        for (int i = 0; i < 10; i++) {
            chat.clock = chat.clock.plusSeconds(120);
            a.tick(chat.clock);
            b.tick(chat.clock);
        }
        assertThat(a.active()).isTrue();
        assertThat(b.active()).isFalse();

        // A 停了 11 分钟，B 接管；A 回来后让出
        chat.clock = chat.clock.plus(Duration.ofMinutes(11));
        b.tick(chat.clock);
        assertThat(b.active()).isTrue();
        assertThat(chat.pins).hasSize(1);
        chat.clock = chat.clock.plusSeconds(30);
        a.tick(chat.clock);
        assertThat(a.active()).isFalse();
        assertThat(a.owner()).contains("PC-B");
    }

    @Test
    void simultaneousClaimsLeaveExactlyOneActive() {
        FakeChat chat = new FakeChat();
        // 两台都在对方置顶之前读到"没有租约"，都发了消息：再读时都看到发送时间最新的那条
        long first = chat.post("x\n" + ActiveLease.TAG + "PC-A");
        chat.pin(first);
        long second = chat.post("x\n" + ActiveLease.TAG + "PC-B");
        chat.pin(second);
        assertThat(ActiveLease.holder(chat.pinned().orElseThrow().text())).contains("PC-B");

        ActiveLease a = lease(new FakeChat(), "PC-A");
        FakeChat shared = new FakeChat();
        ActiveLease a2 = lease(shared, "PC-A");
        ActiveLease b2 = lease(shared, "PC-B");
        a2.tick(shared.clock);
        b2.tick(shared.clock.plus(Duration.ofMinutes(11))); // A 的租约已过期，B 抢占
        a2.tick(shared.clock);
        assertThat(List.of(a2.active(), b2.active())).containsExactlyInAnyOrder(true, false);
        assertThat(a.active()).isFalse(); // 未 tick 前不算运行
    }

    @Test
    void restartOnSameMachineKeepsLeaseAndTelegramOutageKeepsRole() {
        FakeChat chat = new FakeChat();
        lease(chat, "PC-A").tick(chat.clock);
        ActiveLease restarted = lease(chat, "PC-A");
        restarted.tick(chat.clock.plusSeconds(30));
        assertThat(restarted.active()).isTrue();
        assertThat(chat.msgs).hasSize(1); // 续用原来那条置顶

        ActiveLease standby = lease(chat, "PC-B");
        standby.tick(chat.clock.plusSeconds(60));
        chat.down = true;
        standby.tick(chat.clock.plus(Duration.ofMinutes(30)));
        restarted.tick(chat.clock.plus(Duration.ofMinutes(30)));
        assertThat(standby.active()).isFalse();
        assertThat(restarted.active()).isTrue();

        ActiveLease fresh = lease(chat, "PC-C");
        fresh.tick(chat.clock);
        assertThat(fresh.active()).isTrue(); // 启动时就读不到：按单机运行
    }

    @Test
    void parsesHolderFromLeaseText() {
        assertThat(ActiveLease.holder("🟢 TrendPilot 运行中\n机器：X\n" + ActiveLease.TAG + "DESKTOP-1")).contains("DESKTOP-1");
        assertThat(ActiveLease.holder("别的置顶消息")).isEmpty();
        assertThat(ActiveLease.holder(null)).isEmpty();
    }
}
