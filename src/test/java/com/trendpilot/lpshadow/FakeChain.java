package com.trendpilot.lpshadow;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 测试用的假链：按 (合约地址, calldata) 返回事先设定的结果，记录收到的请求。 */
final class FakeChain implements RpcClient.Transport {

    private static final ObjectMapper JSON = new ObjectMapper();

    final Map<String, String> answers = new HashMap<>();
    final List<JsonNode> requests = new ArrayList<>();
    String gasPrice = "0x5f5e100"; // 0.1 gwei
    RuntimeException failWith;

    void on(String to, String data, String result) {
        answers.put(to.toLowerCase(Locale.ROOT) + "|" + data.toLowerCase(Locale.ROOT), result);
    }

    @Override
    public JsonNode post(String body) {
        try {
            JsonNode req = JSON.readTree(body);
            requests.add(req);
            if (failWith != null) throw failWith;
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode r : req) {
                ObjectNode o = out.addObject().put("jsonrpc", "2.0").put("id", r.path("id").asInt());
                if (r.path("method").asText().equals("eth_gasPrice")) {
                    o.put("result", gasPrice);
                    continue;
                }
                JsonNode p = r.path("params").get(0);
                String key = p.path("to").asText().toLowerCase(Locale.ROOT) + "|" + p.path("data").asText().toLowerCase(Locale.ROOT);
                String a = answers.get(key);
                if (a == null) o.putObject("error").put("code", -32000).put("message", "execution reverted");
                else o.put("result", a);
            }
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String uint(BigInteger v) {
        return "0x" + Abi.word(v);
    }

    static String uint(long v) {
        return uint(BigInteger.valueOf(v));
    }

    static String words(BigInteger... vs) {
        StringBuilder b = new StringBuilder("0x");
        for (BigInteger v : vs) b.append(Abi.word(v.signum() < 0 ? v.add(Abi.TWO_256) : v));
        return b.toString();
    }

    static String addr(String a) {
        return "0x" + Abi.word(a);
    }

    static String string(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder();
        for (byte x : b) h.append(String.format("%02x", x));
        while (h.length() % 64 != 0 || h.length() == 0) h.append('0');
        return "0x" + Abi.word(BigInteger.valueOf(32)) + Abi.word(BigInteger.valueOf(b.length)) + h;
    }

    // ----- Base 链的标准配置 -----
    static final String FACTORY = "0x33128a8fC17869897dcE68Ed026d694621f6FDfD";
    static final String WETH = "0x4200000000000000000000000000000000000006";
    static final String USDC = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913";
    static final String POOL_005 = "0xd0b53D9277642d899DF5C87A3966A349A798F224";
    static final String POOL_030 = "0x6c561B446416E1A00E8E93E221854d6eA4171372";

    static LpShadowProperties.Chain baseChain() {
        return new LpShadowProperties.Chain("http://fake", FACTORY, WETH, USDC, null, 0, null, 0, 0);
    }

    /** 设定代币信息和两个池子（WETH 是 token0），池子状态由各测试自己设。 */
    static FakeChain base() {
        FakeChain c = new FakeChain();
        c.on(WETH, Abi.SYMBOL, string("WETH"));
        c.on(WETH, Abi.DECIMALS, uint(18));
        c.on(USDC, Abi.SYMBOL, string("USDC"));
        c.on(USDC, Abi.DECIMALS, uint(6));
        c.pool(POOL_005, 500, 10, WETH, USDC);
        c.pool(POOL_030, 3000, 60, WETH, USDC);
        return c;
    }

    void pool(String address, int fee, int spacing, String t0, String t1) {
        on(FACTORY, Abi.getPool(WETH, USDC, fee), addr(address));
        on(address, Abi.TOKEN0, addr(t0));
        on(address, Abi.TOKEN1, addr(t1));
        on(address, Abi.FEE, uint(fee));
        on(address, Abi.TICK_SPACING, uint(spacing));
        on(address, Abi.FACTORY, addr(FACTORY));
    }

    void state(String address, BigInteger sqrtP, int tick, BigInteger liq, BigInteger fg0, BigInteger fg1) {
        on(address, Abi.SLOT0, words(sqrtP, BigInteger.valueOf(tick), BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE,
                BigInteger.ZERO, BigInteger.ONE));
        on(address, Abi.LIQUIDITY, uint(liq));
        on(address, Abi.FEE_GROWTH_GLOBAL0, uint(fg0));
        on(address, Abi.FEE_GROWTH_GLOBAL1, uint(fg1));
    }
}
