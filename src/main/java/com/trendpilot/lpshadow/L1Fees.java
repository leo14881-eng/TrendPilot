package com.trendpilot.lpshadow;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;

import com.trendpilot.lpshadow.RpcClient.Call;
import com.trendpilot.lpshadow.RpcClient.Result;

/**
 * 一次调仓的 L1 数据费估算（eth_gasPrice 只含 L2 执行费，L2 交易还要付把数据发布到以太坊主网的 L1 费）。
 * 只用链上预部署合约的只读方法（eth_call），每条链 2 个调用并入每分钟的 batch，第一个失败时用第二个：
 * <ul>
 * <li>op-stack（Base）：GasPriceOracle 0x42…0F 的 getL1FeeUpperBound(每笔字节数)（Fjord 起），
 *     备用 getL1Fee(bytes)（传一段固定的不可压缩数据）；结果是每笔 wei；</li>
 * <li>arbitrum：ArbGasInfo 0x…6C 的 getPricesInWei()：每笔 = perL2Tx + perL1CalldataByte × 每笔字节数；
 *     备用 getL1BaseFeeEstimate() × 16 × (每笔字节数 + 140)（Nitro 的同一套算法）。</li>
 * </ul>
 * 一次调仓合计 = 每笔 × 笔数（默认 1,000 字节 × 3 笔：撤出并领取、换币、开新仓）。
 */
final class L1Fees {

    static final String OP_GAS_PRICE_ORACLE = "0x420000000000000000000000000000000000000F";
    static final String ARB_GAS_INFO = "0x000000000000000000000000000000000000006C";
    static final String GET_L1_FEE_UPPER_BOUND = "0xf1c7a58b";   // getL1FeeUpperBound(uint256)
    static final String GET_L1_FEE = "0x49948e0e";               // getL1Fee(bytes)
    static final String GET_PRICES_IN_WEI = "0x41b247a8";        // getPricesInWei()
    static final String GET_L1_BASE_FEE_ESTIMATE = "0xf5d6ded7"; // getL1BaseFeeEstimate()
    static final int ARB_TX_FIXED_BYTES = 140;
    static final int CALLDATA_GAS_PER_BYTE = 16;

    record Estimate(BigInteger totalWei, String source) {
    }

    private L1Fees() {
    }

    /** 这条链要加进 batch 的两个调用；不估算的链返回空。 */
    static List<Call> calls(String kind, int txBytes) {
        if ("op-stack".equals(kind)) {
            return List.of(Call.ethCall(OP_GAS_PRICE_ORACLE, GET_L1_FEE_UPPER_BOUND + Abi.word(BigInteger.valueOf(txBytes))),
                    Call.ethCall(OP_GAS_PRICE_ORACLE, GET_L1_FEE + dynamicBytes(sampleTx(txBytes))));
        }
        if ("arbitrum".equals(kind)) {
            return List.of(Call.ethCall(ARB_GAS_INFO, GET_PRICES_IN_WEI), Call.ethCall(ARB_GAS_INFO, GET_L1_BASE_FEE_ESTIMATE));
        }
        return List.of();
    }

    /** 解析两个调用的结果；都取不到返回 null。 */
    static Estimate parse(String kind, List<Result> r, int txBytes, int txCount) {
        if (r.size() < 2) return null;
        BigInteger n = BigInteger.valueOf(txCount);
        try {
            if ("op-stack".equals(kind)) {
                if (r.get(0).ok()) return new Estimate(Abi.uint(r.get(0).value(), 0).multiply(n), "getL1FeeUpperBound");
                if (r.get(1).ok()) return new Estimate(Abi.uint(r.get(1).value(), 0).multiply(n), "getL1Fee");
            } else if ("arbitrum".equals(kind)) {
                if (r.get(0).ok()) {
                    BigInteger perTx = Abi.uint(r.get(0).value(), 0);
                    BigInteger perByte = Abi.uint(r.get(0).value(), 1);
                    return new Estimate(perTx.add(perByte.multiply(BigInteger.valueOf(txBytes))).multiply(n), "getPricesInWei");
                }
                if (r.get(1).ok()) {
                    BigInteger l1 = Abi.uint(r.get(1).value(), 0);
                    BigInteger units = BigInteger.valueOf((long) CALLDATA_GAS_PER_BYTE * (txBytes + ARB_TX_FIXED_BYTES));
                    return new Estimate(l1.multiply(units).multiply(n), "getL1BaseFeeEstimate");
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** 固定种子的伪随机字节（不可压缩，接近真实交易数据的 L1 费）。 */
    static byte[] sampleTx(int size) {
        byte[] b = new byte[size];
        new Random(20261030L).nextBytes(b);
        for (int i = 0; i < b.length; i++) if (b[i] == 0) b[i] = 1;
        return b;
    }

    /** ABI 编码的单个 bytes 参数：偏移 0x20 + 长度 + 数据（右补零到 32 字节倍数）。 */
    static String dynamicBytes(byte[] data) {
        StringBuilder h = new StringBuilder();
        h.append(Abi.word(BigInteger.valueOf(32))).append(Abi.word(BigInteger.valueOf(data.length)));
        for (byte x : data) h.append(String.format("%02x", x));
        while (h.length() % 64 != 0) h.append('0');
        return h.toString();
    }
}
