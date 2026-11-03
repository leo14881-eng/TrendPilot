package com.trendpilot.lpshadow;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

import com.trendpilot.lpshadow.RpcClient.Call;
import com.trendpilot.lpshadow.RpcClient.Result;

/**
 * 新池子上线前的规模检查（2026-11-03 起用于 BTC 池和稳定币池）：
 * <ul>
 * <li>TVL：链上只读，一个 batch（slot0 + 两个代币的 balanceOf(pool)），按池子价格换成 USDC；</li>
 * <li>24 小时成交量：GeckoTerminal 公开接口（只读、免费，一个池子一次请求）。</li>
 * </ul>
 * 任一项低于门槛 → INACTIVE（不运行，告警说明原因）；数据取不到 → 稍后重试（这段时间池子不运行，不会用猜的数据上线）。
 */
final class PoolSizeCheck {

    /** 24 小时成交量（美元）；取不到返回 null。 */
    interface VolumeSource {
        Double volume24hUsd(String chain, String poolAddress);
    }

    enum Verdict { ACTIVE, INACTIVE, RETRY }

    record Outcome(Verdict verdict, Double tvlUsd, Double volumeUsd, String reason) {
    }

    private PoolSizeCheck() {
    }

    static Outcome check(RpcClient rpc, PoolInfo info, VolumeSource volume, double minTvl, double minVolume) {
        Double tvl = null;
        try {
            List<Result> r = rpc.batch(List.of(Call.ethCall(info.address(), Abi.SLOT0), Call.ethCall(info.token0(), Abi.balanceOf(info.address())),
                    Call.ethCall(info.token1(), Abi.balanceOf(info.address()))));
            if (r.stream().allMatch(Result::ok)) {
                BigInteger sqrtP = Abi.uint(r.get(0).value(), 0);
                double t = LpMath.tvlAndM(info, Abi.uint(r.get(1).value(), 0), Abi.uint(r.get(2).value(), 0), sqrtP, BigInteger.ONE)[0];
                if (Double.isFinite(t) && t >= 0) tvl = t;
            }
        } catch (RuntimeException e) {
            tvl = null;
        }
        if (tvl == null) return new Outcome(Verdict.RETRY, null, null, "链上 TVL 读取失败");
        Double vol;
        try {
            vol = volume.volume24hUsd(info.chain(), info.address());
        } catch (RuntimeException e) {
            vol = null;
        }
        if (vol != null && (!Double.isFinite(vol) || vol < 0)) vol = null;
        if (tvl < minTvl) {
            return new Outcome(Verdict.INACTIVE, tvl, vol, String.format(Locale.ROOT, "TVL %s U 低于门槛 %s U（24 小时成交量 %s U，门槛 %s U）",
                    money(tvl), money(minTvl), vol == null ? "未知" : money(vol), money(minVolume)));
        }
        if (vol == null) return new Outcome(Verdict.RETRY, tvl, null, "24 小时成交量读取失败");
        if (vol < minVolume) {
            return new Outcome(Verdict.INACTIVE, tvl, vol, String.format(Locale.ROOT, "24 小时成交量 %s U 低于门槛 %s U（TVL %s U）",
                    money(vol), money(minVolume), money(tvl)));
        }
        return new Outcome(Verdict.ACTIVE, tvl, vol, String.format(Locale.ROOT, "TVL %s U，24 小时成交量 %s U", money(tvl), money(vol)));
    }

    /** 例：1,234,567 → "123.5 万"。 */
    static String money(double v) {
        return v >= 10_000 ? String.format(Locale.ROOT, "%.1f 万", v / 10_000) : String.format(Locale.ROOT, "%.0f", v);
    }
}
