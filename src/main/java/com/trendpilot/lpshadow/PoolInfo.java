package com.trendpilot.lpshadow;

/**
 * 解析并校验过的池子。token0 / token1 以池子合约返回的为准（按地址大小排序），两条链上 WETH 不一定是 token0。
 *
 * @param fee         费率档（500 = 0.05%）
 * @param wethIsToken0 WETH 是否是 token0
 */
public record PoolInfo(String key, String chain, int fee, String address, String token0, String token1, int dec0, int dec1,
                       boolean wethIsToken0, int tickSpacing) {

    public double feeRate() {
        return fee / 1_000_000.0;
    }

    public int wethDecimals() {
        return wethIsToken0 ? dec0 : dec1;
    }

    public int usdcDecimals() {
        return wethIsToken0 ? dec1 : dec0;
    }
}
