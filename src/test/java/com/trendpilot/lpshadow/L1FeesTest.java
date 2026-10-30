package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trendpilot.lpshadow.RpcClient.Result;

/**
 * L1 数据费估算：选择器、调用编码、按链上返回格式解析（OP Stack / Arbitrum 各自的主方法和备用方法）、都取不到时返回 null。
 * 返回值按合约 ABI 的返回格式构造（数值是数量级合理的样例，不是某个区块的录制值）。
 */
class L1FeesTest {

    static Result ok(String hex) {
        return new Result(hex, null);
    }

    static final Result REVERT = new Result(null, "{\"code\":-32000,\"message\":\"execution reverted\"}");

    @Test
    void selectorsMatchSignatures() {
        assertThat(L1Fees.GET_L1_FEE_UPPER_BOUND).isEqualTo(Keccak.selector("getL1FeeUpperBound(uint256)"));
        assertThat(L1Fees.GET_L1_FEE).isEqualTo(Keccak.selector("getL1Fee(bytes)"));
        assertThat(L1Fees.GET_PRICES_IN_WEI).isEqualTo(Keccak.selector("getPricesInWei()"));
        assertThat(L1Fees.GET_L1_BASE_FEE_ESTIMATE).isEqualTo(Keccak.selector("getL1BaseFeeEstimate()"));
    }

    @Test
    void callsAreReadOnlyEthCallsToPredeploys() {
        List<RpcClient.Call> op = L1Fees.calls("op-stack", 1000);
        assertThat(op).hasSize(2).allMatch(c -> c.method().equals("eth_call") && c.to().equals(L1Fees.OP_GAS_PRICE_ORACLE));
        assertThat(op.get(0).data()).isEqualTo("0xf1c7a58b" + "0".repeat(61) + "3e8");
        // getL1Fee(bytes)：选择器 + 偏移 + 长度 + 1000 字节（补到 1024）
        assertThat(op.get(1).data()).hasSize(10 + 64 + 64 + 1024 * 2);
        List<RpcClient.Call> arb = L1Fees.calls("arbitrum", 1000);
        assertThat(arb).hasSize(2).allMatch(c -> c.method().equals("eth_call") && c.to().equals(L1Fees.ARB_GAS_INFO));
        assertThat(L1Fees.calls(null, 1000)).isEmpty();
    }

    @Test
    void opStackUsesUpperBoundThenFallsBackToGetL1Fee() {
        L1Fees.Estimate e = L1Fees.parse("op-stack", List.of(ok(FakeChain.uint(40_000_000_000L)), ok(FakeChain.uint(1L))), 1000, 3);
        assertThat(e.totalWei()).isEqualTo(BigInteger.valueOf(120_000_000_000L));
        assertThat(e.source()).isEqualTo("getL1FeeUpperBound");
        L1Fees.Estimate f = L1Fees.parse("op-stack", List.of(REVERT, ok(FakeChain.uint(35_000_000_000L))), 1000, 3);
        assertThat(f.totalWei()).isEqualTo(BigInteger.valueOf(105_000_000_000L));
        assertThat(f.source()).isEqualTo("getL1Fee");
        assertThat(L1Fees.parse("op-stack", List.of(REVERT, REVERT), 1000, 3)).isNull();
    }

    @Test
    void arbitrumUsesPricesInWeiThenFallsBackToL1BaseFee() {
        // getPricesInWei：(perL2Tx, perL1CalldataByte, perStorageAllocation, perArbGasBase, perArbGasCongestion, perArbGasTotal)
        String prices = FakeChain.words(BigInteger.valueOf(44_800_000_000L), BigInteger.valueOf(320_000_000L),
                BigInteger.valueOf(200_000_000_000L), BigInteger.valueOf(10_000_000L), BigInteger.ZERO, BigInteger.valueOf(10_000_000L));
        L1Fees.Estimate e = L1Fees.parse("arbitrum", List.of(ok(prices), ok(FakeChain.uint(20_000_000L))), 1000, 3);
        // 每笔 = 4.48e10 + 3.2e8 × 1000 = 3.648e11；3 笔
        assertThat(e.totalWei()).isEqualTo(BigInteger.valueOf(3 * 364_800_000_000L));
        assertThat(e.source()).isEqualTo("getPricesInWei");
        // 备用：l1BaseFeeEstimate 2e7 × 16 × (1000 + 140) × 3
        L1Fees.Estimate f = L1Fees.parse("arbitrum", List.of(REVERT, ok(FakeChain.uint(20_000_000L))), 1000, 3);
        assertThat(f.totalWei()).isEqualTo(BigInteger.valueOf(20_000_000L * 16 * 1140 * 3));
        assertThat(L1Fees.parse("arbitrum", List.of(REVERT, REVERT), 1000, 3)).isNull();
        assertThat(L1Fees.parse("arbitrum", List.of(ok("0x01"), REVERT), 1000, 3)).isNull();   // 返回格式不对
    }
}
