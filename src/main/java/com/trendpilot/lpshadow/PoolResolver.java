package com.trendpilot.lpshadow;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.trendpilot.lpshadow.RpcClient.Call;
import com.trendpilot.lpshadow.RpcClient.Result;

/**
 * 启动时解析池子地址：Factory.getPool(WETH, USDC, fee)，再核对
 * <ul>
 * <li>WETH / USDC 的 symbol() 和 decimals() 与配置一致；</li>
 * <li>池子的 factory()、fee()、tickSpacing() 与期望一致，{token0, token1} 正好是 {WETH, USDC}。</li>
 * </ul>
 * 任何一项不一致：这个池子停用（{@link Outcome#rejected()}），由调用方告警。网络失败只是"暂时解析不了"，下一分钟再试。
 * 每条链两次 batch 请求（第一次 getPool + 代币信息，第二次池子信息）。
 */
final class PoolResolver {

    /** 结果：info 非空 = 通过；rejected = 校验不通过（停用）；否则是网络问题，稍后重试。 */
    record Outcome(PoolInfo info, boolean rejected, String reason) {
        static Outcome ok(PoolInfo i) {
            return new Outcome(i, false, null);
        }

        static Outcome reject(String why) {
            return new Outcome(null, true, why);
        }

        static Outcome retry(String why) {
            return new Outcome(null, false, why);
        }
    }

    static int expectedTickSpacing(int fee) {
        return switch (fee) {
            case 100 -> 1;
            case 500 -> 10;
            case 3000 -> 60;
            case 10000 -> 200;
            default -> -1;
        };
    }

    /** 解析同一条链上的多个池子。 */
    static Map<String, Outcome> resolve(RpcClient rpc, LpShadowProperties.Chain chain, String chainName,
                                        List<LpShadowProperties.Pool> pools) {
        Map<String, Outcome> out = new LinkedHashMap<>();
        List<Call> first = new ArrayList<>();
        first.add(Call.ethCall(chain.weth(), Abi.SYMBOL));
        first.add(Call.ethCall(chain.weth(), Abi.DECIMALS));
        first.add(Call.ethCall(chain.usdc(), Abi.SYMBOL));
        first.add(Call.ethCall(chain.usdc(), Abi.DECIMALS));
        for (LpShadowProperties.Pool p : pools) first.add(Call.ethCall(chain.factory(), Abi.getPool(chain.weth(), chain.usdc(), p.fee())));
        List<Result> r1;
        try {
            r1 = rpc.batch(first);
        } catch (RuntimeException e) {
            pools.forEach(p -> out.put(p.key(), Outcome.retry(e.getMessage())));
            return out;
        }
        if (!r1.get(0).ok() || !r1.get(1).ok() || !r1.get(2).ok() || !r1.get(3).ok()) {
            pools.forEach(p -> out.put(p.key(), Outcome.retry("代币信息读取失败")));
            return out;
        }
        String tokenProblem;
        try {
            tokenProblem = checkToken("WETH", Abi.string(r1.get(0).value()), Abi.uint(r1.get(1).value(), 0), chain.wethSymbol(),
                    chain.wethDecimals());
            if (tokenProblem == null) {
                tokenProblem = checkToken("USDC", Abi.string(r1.get(2).value()), Abi.uint(r1.get(3).value(), 0), chain.usdcSymbol(),
                        chain.usdcDecimals());
            }
        } catch (RuntimeException e) {
            tokenProblem = "代币信息无法解码：" + e.getMessage();
        }
        if (tokenProblem != null) {
            String why = tokenProblem;
            pools.forEach(p -> out.put(p.key(), Outcome.reject(why)));
            return out;
        }

        Map<String, String> addresses = new LinkedHashMap<>();
        List<Call> second = new ArrayList<>();
        for (int i = 0; i < pools.size(); i++) {
            LpShadowProperties.Pool p = pools.get(i);
            Result r = r1.get(4 + i);
            if (!r.ok()) {
                out.put(p.key(), Outcome.retry("getPool 读取失败"));
                continue;
            }
            String addr = Abi.address(r.value(), 0);
            if (new BigInteger(Abi.strip(addr), 16).signum() == 0) {
                out.put(p.key(), Outcome.reject("Factory 上没有费率 " + p.fee() + " 的 WETH/USDC 池子"));
                continue;
            }
            addresses.put(p.key(), addr);
            for (String sel : List.of(Abi.TOKEN0, Abi.TOKEN1, Abi.FEE, Abi.TICK_SPACING, Abi.FACTORY)) second.add(Call.ethCall(addr, sel));
        }
        if (addresses.isEmpty()) return out;
        List<Result> r2;
        try {
            r2 = rpc.batch(second);
        } catch (RuntimeException e) {
            addresses.keySet().forEach(k -> out.put(k, Outcome.retry(e.getMessage())));
            return out;
        }
        int j = 0;
        for (LpShadowProperties.Pool p : pools) {
            String addr = addresses.get(p.key());
            if (addr == null) continue;
            List<Result> rs = r2.subList(j, j + 5);
            j += 5;
            if (!rs.stream().allMatch(Result::ok)) {
                out.put(p.key(), Outcome.retry("池子信息读取失败"));
                continue;
            }
            try {
                String t0 = Abi.address(rs.get(0).value(), 0);
                String t1 = Abi.address(rs.get(1).value(), 0);
                int fee = Abi.uint(rs.get(2).value(), 0).intValueExact();
                int spacing = Abi.int256(rs.get(3).value(), 0).intValueExact();
                String factory = Abi.address(rs.get(4).value(), 0);
                if (!Abi.sameAddress(factory, chain.factory())) {
                    out.put(p.key(), Outcome.reject("池子的 factory 不是配置的 Uniswap V3 Factory"));
                } else if (fee != p.fee()) {
                    out.put(p.key(), Outcome.reject("池子费率 " + fee + " 不是 " + p.fee()));
                } else if (spacing != expectedTickSpacing(p.fee())) {
                    out.put(p.key(), Outcome.reject("tickSpacing " + spacing + " 不符合费率 " + p.fee()));
                } else if (Abi.sameAddress(t0, chain.weth()) && Abi.sameAddress(t1, chain.usdc())) {
                    out.put(p.key(), Outcome.ok(new PoolInfo(p.key(), chainName, fee, addr, t0, t1, chain.wethDecimals(),
                            chain.usdcDecimals(), true, spacing)));
                } else if (Abi.sameAddress(t0, chain.usdc()) && Abi.sameAddress(t1, chain.weth())) {
                    out.put(p.key(), Outcome.ok(new PoolInfo(p.key(), chainName, fee, addr, t0, t1, chain.usdcDecimals(),
                            chain.wethDecimals(), false, spacing)));
                } else {
                    out.put(p.key(), Outcome.reject("池子的两个代币不是 WETH / USDC"));
                }
            } catch (RuntimeException e) {
                out.put(p.key(), Outcome.reject("池子信息无法解码：" + e.getMessage()));
            }
        }
        return out;
    }

    static String checkToken(String label, String symbol, BigInteger decimals, String wantSymbol, int wantDecimals) {
        if (!wantSymbol.equals(symbol)) return label + " 的 symbol 是 " + symbol + "，期望 " + wantSymbol;
        if (decimals.intValue() != wantDecimals || decimals.bitLength() > 8) {
            return label + " 的 decimals 是 " + decimals + "，期望 " + wantDecimals;
        }
        return null;
    }
}
