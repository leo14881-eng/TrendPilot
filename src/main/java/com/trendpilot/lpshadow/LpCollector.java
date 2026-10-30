package com.trendpilot.lpshadow;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.trendpilot.lpshadow.RpcClient.Call;
import com.trendpilot.lpshadow.RpcClient.Result;

/**
 * 每分钟采集一次：每条链一个 batch（该链所有池子的 slot0、liquidity、feeGrowthGlobal0/1，加一个 eth_gasPrice），
 * 永续报价每分钟最多一次（{@link PerpQuotes}），每小时每条链再读一次 balanceOf 算 TVL 和集中度。
 * 拿不到的池子这一分钟写 GAP 行（note 写原因），不会用旧数据补。
 */
final class LpCollector {

    private static final Logger log = LoggerFactory.getLogger(LpCollector.class);
    /** 一条链连续多少分钟不通才发一次 Telegram（每天最多一次）。 */
    static final int ALERT_AFTER_MINUTES = 30;

    private final LpShadowProperties props;
    private final Map<String, RpcClient> rpc;
    private final PerpQuotes perp;
    private final LpStore store;
    /** (eventKey, text) → 通知。 */
    private final BiConsumer<String, String> alert;

    private final Map<String, PoolInfo> active = new LinkedHashMap<>();
    private final Set<String> rejected = new HashSet<>();
    private final Map<String, Integer> failStreak = new HashMap<>();
    private final Map<String, Snapshot> latest = new HashMap<>();

    LpCollector(LpShadowProperties props, Map<String, RpcClient> rpc, PerpQuotes perp, LpStore store,
                BiConsumer<String, String> alert) {
        this.props = props;
        this.rpc = rpc;
        this.perp = perp;
        this.store = store;
        this.alert = alert;
    }

    Map<String, PoolInfo> activePools() {
        return Map.copyOf(active);
    }

    /** 每个池子最近一次快照（内存里只留这一份）。 */
    Map<String, Snapshot> latest() {
        return Map.copyOf(latest);
    }

    /** 采集一分钟；ts = 整分钟的 epoch 秒。返回写入的快照（每个配置的池子一行，已停用的除外）。 */
    List<Snapshot> collect(long ts, Instant now) {
        resolvePending(now);
        PerpQuotes.Quote q = perp.get(now.toEpochMilli());
        Double mark = q == null ? null : q.mark();
        Double funding = q == null ? null : q.fundingRate();
        List<Snapshot> rows = new ArrayList<>();
        for (Map.Entry<String, List<LpShadowProperties.Pool>> e : poolsByChain().entrySet()) {
            String chain = e.getKey();
            List<LpShadowProperties.Pool> pools = e.getValue().stream().filter(p -> !rejected.contains(p.key())).toList();
            List<PoolInfo> infos = pools.stream().map(p -> active.get(p.key())).filter(java.util.Objects::nonNull).toList();
            for (LpShadowProperties.Pool p : pools) {
                if (!active.containsKey(p.key())) rows.add(Snapshot.gap(ts, p.key(), mark, funding, "池子地址还没解析成功"));
            }
            if (infos.isEmpty()) continue;
            List<Call> calls = new ArrayList<>();
            for (PoolInfo i : infos) {
                calls.add(Call.ethCall(i.address(), Abi.SLOT0));
                calls.add(Call.ethCall(i.address(), Abi.LIQUIDITY));
                calls.add(Call.ethCall(i.address(), Abi.FEE_GROWTH_GLOBAL0));
                calls.add(Call.ethCall(i.address(), Abi.FEE_GROWTH_GLOBAL1));
            }
            calls.add(Call.gasPrice());
            LpShadowProperties.Chain chainCfg = props.chains().get(chain);
            String l1Kind = chainCfg == null ? null : chainCfg.l1Fee();
            List<Call> l1Calls = L1Fees.calls(l1Kind, props.l1TxBytes());
            calls.addAll(l1Calls);
            List<Result> rs;
            try {
                rs = rpc.get(chain).batch(calls);
                chainOk(chain);
            } catch (RuntimeException ex) {
                chainFailed(chain, ex.getMessage(), now);
                for (PoolInfo i : infos) rows.add(Snapshot.gap(ts, i.key(), mark, funding, "RPC：" + RpcClient.abbreviate(String.valueOf(ex.getMessage()))));
                continue;
            }
            int gasIdx = infos.size() * 4;
            Result gasR = rs.get(gasIdx);
            BigInteger gas = gasR.ok() ? Abi.quantity(gasR.value()) : null;
            L1Fees.Estimate l1 = L1Fees.parse(l1Kind, rs.subList(gasIdx + 1, rs.size()), props.l1TxBytes(), props.l1TxCount());
            for (int k = 0; k < infos.size(); k++) {
                Snapshot snap = parse(ts, infos.get(k), rs.subList(k * 4, k * 4 + 4), gas, mark, funding);
                rows.add(l1 == null ? snap : snap.withL1(l1.totalWei(), l1.source()));
            }
        }
        store.insertSnapshots(rows);
        for (Snapshot s : rows) latest.put(s.pool(), s);
        collectTvl(ts, rows);
        return rows;
    }

    static Snapshot parse(long ts, PoolInfo p, List<Result> r, BigInteger gas, Double mark, Double funding) {
        for (Result x : r) {
            if (!x.ok()) return Snapshot.gap(ts, p.key(), mark, funding, "eth_call 失败：" + (x.error() == null ? "空结果" : x.error()));
        }
        try {
            BigInteger sqrtP = Abi.uint(r.get(0).value(), 0);
            int tick = Abi.int256(r.get(0).value(), 1).intValueExact();
            BigInteger liq = Abi.uint(r.get(1).value(), 0);
            BigInteger fg0 = Abi.uint(r.get(2).value(), 0);
            BigInteger fg1 = Abi.uint(r.get(3).value(), 0);
            if (sqrtP.signum() <= 0 || tick < LpMath.MIN_TICK || tick > LpMath.MAX_TICK) {
                return Snapshot.gap(ts, p.key(), mark, funding, "slot0 数值不合理");
            }
            return new Snapshot(ts, p.key(), sqrtP, tick, liq, fg0, fg1, gas, mark, funding, Snapshot.OK, null);
        } catch (RuntimeException e) {
            return Snapshot.gap(ts, p.key(), mark, funding, "解码失败：" + e.getMessage());
        }
    }

    private Map<String, List<LpShadowProperties.Pool>> poolsByChain() {
        Map<String, List<LpShadowProperties.Pool>> out = new LinkedHashMap<>();
        for (LpShadowProperties.Pool p : props.pools()) out.computeIfAbsent(p.chain(), c -> new ArrayList<>()).add(p);
        return out;
    }

    /** 还没解析成功的池子：按链解析一次（失败的下一分钟再试）。 */
    private void resolvePending(Instant now) {
        for (Map.Entry<String, List<LpShadowProperties.Pool>> e : poolsByChain().entrySet()) {
            List<LpShadowProperties.Pool> pending = e.getValue().stream()
                    .filter(p -> !active.containsKey(p.key()) && !rejected.contains(p.key())).toList();
            if (pending.isEmpty()) continue;
            LpShadowProperties.Chain chain = props.chains().get(e.getKey());
            RpcClient client = rpc.get(e.getKey());
            if (chain == null || client == null) {
                for (LpShadowProperties.Pool p : pending) reject(p, "没有配置链 " + e.getKey(), now);
                continue;
            }
            Map<String, PoolResolver.Outcome> res = PoolResolver.resolve(client, chain, e.getKey(), pending);
            for (LpShadowProperties.Pool p : pending) {
                PoolResolver.Outcome o = res.get(p.key());
                if (o == null) continue;
                if (o.info() != null) {
                    active.put(p.key(), o.info());
                    store.savePool(p.key(), p.chain(), p.fee(), o.info(), "ACTIVE", null, now);
                    log.info("LP 影子盘：池子 {} 已解析 {}（WETH 是 token{}）", p.key(), o.info().address(),
                            o.info().wethIsToken0() ? 0 : 1);
                } else if (o.rejected()) {
                    reject(p, o.reason(), now);
                } else {
                    store.savePool(p.key(), p.chain(), p.fee(), null, "PENDING", o.reason(), now);
                }
            }
        }
    }

    private void reject(LpShadowProperties.Pool p, String why, Instant now) {
        rejected.add(p.key());
        store.savePool(p.key(), p.chain(), p.fee(), null, "REJECTED", why, now);
        log.warn("LP 影子盘：池子 {} 校验未通过，已停用：{}", p.key(), why);
        alert.accept("lpshadow:reject:" + p.key() + ":" + now.toString().substring(0, 10),
                "【LP】池子 " + p.key() + " 校验未通过，已停用：" + why + "。其他池子照常采集。");
    }

    private void chainOk(String chain) {
        Integer n = failStreak.put(chain, 0);
        if (n != null && n > 0) log.info("LP 影子盘：{} RPC 恢复（之前连续 {} 分钟失败）", chain, n);
    }

    private void chainFailed(String chain, String why, Instant now) {
        int n = failStreak.merge(chain, 1, Integer::sum);
        if (n == 1 || n % 60 == 0) log.warn("LP 影子盘：{} RPC 失败（连续 {} 分钟），这段时间记为 gap：{}", chain, n, why);
        if (n == ALERT_AFTER_MINUTES) {
            alert.accept("lpshadow:rpc-down:" + chain + ":" + now.toString().substring(0, 10),
                    "【LP】" + chain + " 公开 RPC 已连续 " + n + " 分钟取不到数据，这段时间记为 gap（不计费、不调仓），恢复后自动继续。");
        }
    }

    /** 每小时每个池子一行 TVL：用这一分钟的 sqrtPrice 和 liquidity，加两个代币的 balanceOf(pool)（每条链一个 batch）。 */
    private void collectTvl(long ts, List<Snapshot> rows) {
        long hour = ts - Math.floorMod(ts, 3600);
        Map<String, List<Snapshot>> byChain = new LinkedHashMap<>();
        for (Snapshot s : rows) {
            PoolInfo i = active.get(s.pool());
            if (i == null || !s.ok() || store.hasTvl(s.pool(), hour)) continue;
            byChain.computeIfAbsent(i.chain(), c -> new ArrayList<>()).add(s);
        }
        for (Map.Entry<String, List<Snapshot>> e : byChain.entrySet()) {
            List<Call> calls = new ArrayList<>();
            for (Snapshot s : e.getValue()) {
                PoolInfo i = active.get(s.pool());
                calls.add(Call.ethCall(i.token0(), Abi.balanceOf(i.address())));
                calls.add(Call.ethCall(i.token1(), Abi.balanceOf(i.address())));
            }
            List<Result> rs;
            try {
                rs = rpc.get(e.getKey()).batch(calls);
            } catch (RuntimeException ex) {
                log.debug("LP 影子盘：{} TVL 读取失败，下一分钟再试：{}", e.getKey(), ex.getMessage());
                continue;
            }
            for (int k = 0; k < e.getValue().size(); k++) {
                Snapshot s = e.getValue().get(k);
                Result b0 = rs.get(k * 2), b1 = rs.get(k * 2 + 1);
                if (!b0.ok() || !b1.ok()) continue;
                BigInteger bal0 = Abi.uint(b0.value(), 0), bal1 = Abi.uint(b1.value(), 0);
                double[] tm = LpMath.tvlAndM(active.get(s.pool()), bal0, bal1, s.sqrtPriceX96(), s.liquidity());
                if (Double.isFinite(tm[0]) && Double.isFinite(tm[1])) {
                    store.insertTvl(s.pool(), hour, bal0, bal1, s.sqrtPriceX96(), s.liquidity(), tm[0], tm[1]);
                }
            }
        }
    }
}
