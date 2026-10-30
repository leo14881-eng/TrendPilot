package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

/** 函数选择器、编码解码、feeGrowth 溢出回绕。 */
class AbiTest {

    @Test
    void keccakMatchesKnownVectors() {
        assertThat(Keccak.selector("transfer(address,uint256)")).isEqualTo("0xa9059cbb");
        assertThat(Keccak.selector("balanceOf(address)")).isEqualTo("0x70a08231");
    }

    @Test
    void selectorConstantsMatchSignatures() {
        assertThat(Abi.GET_POOL).isEqualTo(Keccak.selector("getPool(address,address,uint24)"));
        assertThat(Abi.SLOT0).isEqualTo(Keccak.selector("slot0()"));
        assertThat(Abi.LIQUIDITY).isEqualTo(Keccak.selector("liquidity()"));
        assertThat(Abi.FEE_GROWTH_GLOBAL0).isEqualTo(Keccak.selector("feeGrowthGlobal0X128()"));
        assertThat(Abi.FEE_GROWTH_GLOBAL1).isEqualTo(Keccak.selector("feeGrowthGlobal1X128()"));
        assertThat(Abi.TOKEN0).isEqualTo(Keccak.selector("token0()"));
        assertThat(Abi.TOKEN1).isEqualTo(Keccak.selector("token1()"));
        assertThat(Abi.FEE).isEqualTo(Keccak.selector("fee()"));
        assertThat(Abi.TICK_SPACING).isEqualTo(Keccak.selector("tickSpacing()"));
        assertThat(Abi.FACTORY).isEqualTo(Keccak.selector("factory()"));
        assertThat(Abi.SYMBOL).isEqualTo(Keccak.selector("symbol()"));
        assertThat(Abi.DECIMALS).isEqualTo(Keccak.selector("decimals()"));
        assertThat(Abi.BALANCE_OF).isEqualTo(Keccak.selector("balanceOf(address)"));
    }

    @Test
    void encodesGetPoolArguments() {
        String data = Abi.getPool(FakeChain.WETH, FakeChain.USDC, 500);
        assertThat(data).hasSize(10 + 3 * 64);
        assertThat(data).startsWith("0x1698ee82" + "0".repeat(24) + "4200000000000000000000000000000000000006");
        assertThat(data).endsWith("1f4");
        assertThatThrownBy(() -> Abi.word("0x1234")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodesNegativeTickStringAndAddress() {
        String slot0 = FakeChain.words(BigInteger.TEN, BigInteger.valueOf(-195_000));
        assertThat(Abi.int256(slot0, 1).intValueExact()).isEqualTo(-195_000);
        assertThat(Abi.uint(slot0, 0)).isEqualTo(BigInteger.TEN);
        assertThat(Abi.string(FakeChain.string("WETH"))).isEqualTo("WETH");
        // bytes32 形式的 symbol
        assertThat(Abi.string("0x" + "55534443" + "0".repeat(56))).isEqualTo("USDC");
        assertThat(Abi.address(FakeChain.addr(FakeChain.USDC), 0)).isEqualToIgnoringCase(FakeChain.USDC);
        assertThat(Abi.sameAddress("0xABCDEF0000000000000000000000000000000001", "0xabcdef0000000000000000000000000000000001")).isTrue();
    }

    @Test
    void feeGrowthDeltaWrapsAroundTwoPow256() {
        BigInteger max = Abi.TWO_256.subtract(BigInteger.ONE);
        BigInteger before = max.subtract(BigInteger.valueOf(99));   // 2^256 − 100
        BigInteger after = BigInteger.valueOf(250);                 // 回绕后
        assertThat(Abi.deltaMod256(after, before)).isEqualTo(BigInteger.valueOf(350));
        assertThat(Abi.deltaMod256(BigInteger.valueOf(500), BigInteger.valueOf(200))).isEqualTo(BigInteger.valueOf(300));
        assertThat(Abi.deltaMod256(before, before)).isEqualTo(BigInteger.ZERO);
        // 经过 ABI 解码的大数也一样
        BigInteger decoded = Abi.uint(FakeChain.uint(before), 0);
        assertThat(Abi.deltaMod256(Abi.uint(FakeChain.uint(after), 0), decoded)).isEqualTo(BigInteger.valueOf(350));
    }
}
