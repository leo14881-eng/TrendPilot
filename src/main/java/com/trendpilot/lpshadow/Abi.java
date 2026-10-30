package com.trendpilot.lpshadow;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 手写的最小 ABI 编解码，只覆盖本模块用到的只读调用（view 函数）：地址 / uint 参数编码，
 * uint256 / int256 / address / string 返回值解码。函数选择器写成常量，由测试用 keccak256 核对。
 */
final class Abi {

    static final BigInteger TWO_256 = BigInteger.ONE.shiftLeft(256);
    static final BigInteger TWO_255 = BigInteger.ONE.shiftLeft(255);

    // Uniswap V3 Factory
    static final String GET_POOL = "0x1698ee82";           // getPool(address,address,uint24)
    // Uniswap V3 Pool
    static final String SLOT0 = "0x3850c7bd";              // slot0()
    static final String LIQUIDITY = "0x1a686502";          // liquidity()
    static final String FEE_GROWTH_GLOBAL0 = "0xf3058399"; // feeGrowthGlobal0X128()
    static final String FEE_GROWTH_GLOBAL1 = "0x46141319"; // feeGrowthGlobal1X128()
    static final String TOKEN0 = "0x0dfe1681";             // token0()
    static final String TOKEN1 = "0xd21220a7";             // token1()
    static final String FEE = "0xddca3f43";                // fee()
    static final String TICK_SPACING = "0xd0c93a7c";       // tickSpacing()
    static final String FACTORY = "0xc45a0155";            // factory()
    // ERC-20
    static final String SYMBOL = "0x95d89b41";             // symbol()
    static final String DECIMALS = "0x313ce567";           // decimals()
    static final String BALANCE_OF = "0x70a08231";         // balanceOf(address)

    private Abi() {
    }

    static String getPool(String tokenA, String tokenB, int fee) {
        return GET_POOL + word(tokenA) + word(tokenB) + word(BigInteger.valueOf(fee));
    }

    static String balanceOf(String owner) {
        return BALANCE_OF + word(owner);
    }

    /** 地址左补零到 32 字节。 */
    static String word(String address) {
        String a = strip(address).toLowerCase(Locale.ROOT);
        if (a.length() != 40 || !a.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("不是地址：" + address);
        return "0".repeat(24) + a;
    }

    static String word(BigInteger v) {
        if (v.signum() < 0 || v.bitLength() > 256) throw new IllegalArgumentException("超出 uint256：" + v);
        String h = v.toString(16);
        return "0".repeat(64 - h.length()) + h;
    }

    static String strip(String hex) {
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }

    /** 返回值里第 i 个 32 字节字（无符号）。 */
    static BigInteger uint(String result, int i) {
        String h = strip(result);
        if (h.length() < (i + 1) * 64) throw new IllegalArgumentException("返回值太短：" + h.length() / 2 + " 字节");
        return new BigInteger(h.substring(i * 64, (i + 1) * 64), 16);
    }

    /** 第 i 个字按 int256（补码）解码，用于 int24 tick。 */
    static BigInteger int256(String result, int i) {
        BigInteger u = uint(result, i);
        return u.compareTo(TWO_255) >= 0 ? u.subtract(TWO_256) : u;
    }

    static String address(String result, int i) {
        BigInteger u = uint(result, i);
        if (u.bitLength() > 160) throw new IllegalArgumentException("不是地址返回值");
        String h = u.toString(16);
        return "0x" + "0".repeat(40 - h.length()) + h;
    }

    /** string 返回值（动态类型：偏移 + 长度 + 数据）；少数老代币返回 bytes32，也兼容。 */
    static String string(String result) {
        String h = strip(result);
        if (h.length() == 64) {
            byte[] b = bytes(h);
            int n = 0;
            while (n < b.length && b[n] != 0) n++;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        }
        int offset = uint(result, 0).intValueExact();
        int start = offset * 2;
        int len = new BigInteger(h.substring(start, start + 64), 16).intValueExact();
        if (len > 256) throw new IllegalArgumentException("string 太长");
        return new String(bytes(h.substring(start + 64, start + 64 + len * 2)), StandardCharsets.UTF_8);
    }

    static byte[] bytes(String hex) {
        String h = strip(hex);
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    /** uint256 计数器（feeGrowthGlobal）的差值：按 mod 2^256 处理溢出回绕。 */
    static BigInteger deltaMod256(BigInteger later, BigInteger earlier) {
        return later.subtract(earlier).mod(TWO_256);
    }

    static BigInteger quantity(String hexQuantity) {
        return new BigInteger(strip(hexQuantity), 16);
    }

    static boolean sameAddress(String a, String b) {
        return a != null && b != null && strip(a).equalsIgnoreCase(strip(b));
    }
}
