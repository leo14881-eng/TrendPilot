package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 池子地址解析和校验：WETH 是 token0 / token1 两种顺序；任一项不一致就停用；网络失败只是稍后重试。 */
class PoolResolverTest {

    static final List<LpShadowProperties.Pool> POOLS = List.of(new LpShadowProperties.Pool("base-005", "base", 500),
            new LpShadowProperties.Pool("base-030", "base", 3000));

    Map<String, PoolResolver.Outcome> resolve(FakeChain c) {
        return PoolResolver.resolve(new RpcClient("base", c, 0, () -> 0L), FakeChain.baseChain(), "base", POOLS);
    }

    @Test
    void resolvesAndValidatesBothPools() {
        FakeChain c = FakeChain.base();
        var r = resolve(c);
        PoolInfo p = r.get("base-005").info();
        assertThat(p).isNotNull();
        assertThat(p.address()).isEqualToIgnoringCase(FakeChain.POOL_005);
        assertThat(p.wethIsToken0()).isTrue();
        assertThat(p.dec0()).isEqualTo(18);
        assertThat(p.dec1()).isEqualTo(6);
        assertThat(p.tickSpacing()).isEqualTo(10);
        assertThat(r.get("base-030").info().tickSpacing()).isEqualTo(60);
        assertThat(c.requests).hasSize(2);   // 两个 batch
        assertThat(c.requests.get(0)).hasSize(6);
    }

    @Test
    void usdcAsToken0IsDetected() {
        FakeChain c = FakeChain.base();
        c.pool(FakeChain.POOL_005, 500, 10, FakeChain.USDC, FakeChain.WETH);
        PoolInfo p = resolve(c).get("base-005").info();
        assertThat(p.wethIsToken0()).isFalse();
        assertThat(p.dec0()).isEqualTo(6);
        assertThat(p.dec1()).isEqualTo(18);
        assertThat(p.wethDecimals()).isEqualTo(18);
    }

    @Test
    void wrongSymbolRejectsAllPoolsOnChain() {
        FakeChain c = FakeChain.base();
        c.on(FakeChain.USDC, Abi.SYMBOL, FakeChain.string("USDbC"));
        var r = resolve(c);
        assertThat(r.get("base-005").rejected()).isTrue();
        assertThat(r.get("base-005").reason()).contains("USDbC");
        assertThat(r.get("base-030").rejected()).isTrue();
    }

    @Test
    void wrongDecimalsRejects() {
        FakeChain c = FakeChain.base();
        c.on(FakeChain.WETH, Abi.DECIMALS, FakeChain.uint(8));
        assertThat(resolve(c).get("base-005").rejected()).isTrue();
    }

    @Test
    void missingPoolOrWrongTokensOrFactoryRejectsOnlyThatPool() {
        FakeChain c = FakeChain.base();
        c.on(FakeChain.FACTORY, Abi.getPool(FakeChain.WETH, FakeChain.USDC, 3000), FakeChain.uint(0));
        c.on(FakeChain.POOL_005, Abi.FACTORY, FakeChain.addr("0x1111111111111111111111111111111111111111"));
        var r = resolve(c);
        assertThat(r.get("base-030").rejected()).isTrue();
        assertThat(r.get("base-005").rejected()).isTrue();
        assertThat(r.get("base-005").reason()).contains("factory");

        FakeChain d = FakeChain.base();
        d.pool(FakeChain.POOL_005, 500, 10, FakeChain.WETH, "0x2222222222222222222222222222222222222222");
        assertThat(resolve(d).get("base-005").rejected()).isTrue();
        assertThat(resolve(d).get("base-030").info()).isNotNull();

        FakeChain e = FakeChain.base();
        e.pool(FakeChain.POOL_005, 500, 60, FakeChain.WETH, FakeChain.USDC);
        assertThat(resolve(e).get("base-005").reason()).contains("tickSpacing");
    }

    @Test
    void networkFailureIsRetryNotReject() {
        FakeChain c = FakeChain.base();
        c.failWith = new RpcClient.RpcException("timeout");
        var r = resolve(c);
        assertThat(r.get("base-005").info()).isNull();
        assertThat(r.get("base-005").rejected()).isFalse();
    }
}
