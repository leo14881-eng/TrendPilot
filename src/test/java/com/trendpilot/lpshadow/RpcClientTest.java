package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendpilot.lpshadow.RpcClient.Call;

/** 只读方法白名单、batch 结果按 id 对应、429 / RPC 限流时暂停（固定时钟）。 */
class RpcClientTest {

    final AtomicLong clock = new AtomicLong(1_000_000);

    @Test
    void onlyReadMethodsAreAllowed() {
        RpcClient c = new RpcClient("t", FakeChain.base(), 0, clock::get);
        assertThatThrownBy(() -> c.batch(List.of(new Call("eth_sendRawTransaction", null, "0x00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RpcClient.ALLOWED_METHODS).containsExactlyInAnyOrder("eth_call", "eth_gasPrice");
    }

    @Test
    void resultsAreMatchedById() throws Exception {
        ObjectMapper m = new ObjectMapper();
        RpcClient c = new RpcClient("t", body -> {
            try {
                return m.readTree("[{\"id\":1,\"result\":\"0x02\"},{\"id\":0,\"result\":\"0x01\"},{\"id\":2,\"error\":{\"code\":3,\"message\":\"reverted\"}}]");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, 0, clock::get);
        var r = c.batch(List.of(Call.gasPrice(), Call.gasPrice(), Call.gasPrice()));
        assertThat(r.get(0).value()).isEqualTo("0x01");
        assertThat(r.get(1).value()).isEqualTo("0x02");
        assertThat(r.get(2).ok()).isFalse();
    }

    @Test
    void http429PausesUsingRetryAfterThenRecovers() {
        FakeChain chain = FakeChain.base();
        RpcClient c = new RpcClient("t", chain, 0, clock::get);
        chain.failWith = new RpcClient.RateLimited(7_000);
        assertThatThrownBy(() -> c.batch(List.of(Call.gasPrice()))).isInstanceOf(RpcClient.RpcException.class);
        chain.failWith = null;
        int before = chain.requests.size();
        clock.addAndGet(6_000);
        assertThatThrownBy(() -> c.batch(List.of(Call.gasPrice()))).hasMessageContaining("暂停");
        assertThat(chain.requests).hasSize(before);            // 暂停期间不发请求
        clock.addAndGet(1_500);
        assertThat(c.batch(List.of(Call.gasPrice())).get(0).ok()).isTrue();
    }

    @Test
    void longRetryAfterIsHonouredInFull() {
        FakeChain chain = FakeChain.base();
        RpcClient c = new RpcClient("t", chain, 0, clock::get);
        chain.failWith = new RpcClient.RateLimited(900_000);   // 公开 RPC 要求等 15 分钟
        assertThatThrownBy(() -> c.batch(List.of(Call.gasPrice()))).isInstanceOf(RpcClient.RpcException.class);
        assertThat(c.pausedUntilMs() - clock.get()).isEqualTo(900_000);
    }

    @Test
    void repeated429BacksOffExponentiallyWithoutRetryAfter() {
        FakeChain chain = FakeChain.base();
        RpcClient c = new RpcClient("t", chain, 0, clock::get);
        chain.failWith = new RpcClient.RateLimited(-1);
        assertThatThrownBy(() -> c.batch(List.of(Call.gasPrice()))).isInstanceOf(RpcClient.RpcException.class);
        assertThat(c.pausedUntilMs() - clock.get()).isEqualTo(2_000);
        clock.addAndGet(2_000);
        assertThatThrownBy(() -> c.batch(List.of(Call.gasPrice()))).isInstanceOf(RpcClient.RpcException.class);
        assertThat(c.pausedUntilMs() - clock.get()).isEqualTo(4_000);
    }

    @Test
    void rpcRateLimitErrorAlsoPauses() throws Exception {
        ObjectMapper m = new ObjectMapper();
        RpcClient c = new RpcClient("t", body -> {
            try {
                return m.readTree("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32005,\"message\":\"rate limit exceeded\"}}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, 0, clock::get);
        assertThatThrownBy(() -> c.batch(List.of(Call.gasPrice()))).hasMessageContaining("限流");
        assertThat(c.pausedUntilMs()).isGreaterThan(clock.get());
    }
}
