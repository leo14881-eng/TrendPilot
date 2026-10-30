package com.trendpilot.lpshadow;

import java.math.BigInteger;

/**
 * 一个池子在一分钟采样点的链上状态（lp_pool_snapshot 一行）。status = OK 时池子字段齐全；
 * GAP 时池子字段可能为空，note 写原因。perpMark / fundingRate 是 Binance ETHUSDT 永续的标记价格和资金费率，取不到为 null。
 * l1FeeWei 是按这一分钟链上数据估算的"一次调仓"的 L1 数据费（所有交易合计，wei），l1Source 写用的是哪个只读方法；取不到为 null。
 *
 * @param ts 采样分钟（UTC epoch 秒，整分钟）
 */
public record Snapshot(long ts, String pool, BigInteger sqrtPriceX96, Integer tick, BigInteger liquidity, BigInteger fg0,
                       BigInteger fg1, BigInteger gasPriceWei, Double perpMark, Double fundingRate, String status, String note,
                       BigInteger l1FeeWei, String l1Source) {

    public Snapshot(long ts, String pool, BigInteger sqrtPriceX96, Integer tick, BigInteger liquidity, BigInteger fg0, BigInteger fg1,
                    BigInteger gasPriceWei, Double perpMark, Double fundingRate, String status, String note) {
        this(ts, pool, sqrtPriceX96, tick, liquidity, fg0, fg1, gasPriceWei, perpMark, fundingRate, status, note, null, null);
    }

    public Snapshot withL1(BigInteger fee, String source) {
        return new Snapshot(ts, pool, sqrtPriceX96, tick, liquidity, fg0, fg1, gasPriceWei, perpMark, fundingRate, status, note, fee, source);
    }

    public static final String OK = "OK";
    public static final String GAP = "GAP";

    public boolean ok() {
        return OK.equals(status) && sqrtPriceX96 != null && tick != null && liquidity != null && fg0 != null && fg1 != null;
    }

    public boolean perpOk() {
        return perpMark != null && perpMark > 0 && fundingRate != null;
    }

    public static Snapshot gap(long ts, String pool, Double perpMark, Double fundingRate, String note) {
        return new Snapshot(ts, pool, null, null, null, null, null, null, perpMark, fundingRate, GAP, note);
    }
}
