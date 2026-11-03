package com.trendpilot.lpshadow;

/**
 * 解析并校验过的池子。token0 / token1 以池子合约返回的为准（按地址大小排序），两条链上 WETH 不一定是 token0。
 * 字段名沿用 ETH 池的叫法："weth" 指和 USDC 配对的那个代币（ETH 池是 WETH，BTC 池是 cbBTC / WBTC，稳定币池是 USDT），
 * 价格都是"1 个这种代币值多少 USDC"。
 *
 * @param fee         费率档（500 = 0.05%）
 * @param wethIsToken0 base 代币（WETH / BTC / USDT）是否是 token0
 * @param asset       ETH / BTC / STABLE
 */
public record PoolInfo(String key, String chain, int fee, String address, String token0, String token1, int dec0, int dec1,
                       boolean wethIsToken0, int tickSpacing, String asset) {

    public PoolInfo(String key, String chain, int fee, String address, String token0, String token1, int dec0, int dec1,
                    boolean wethIsToken0, int tickSpacing) {
        this(key, chain, fee, address, token0, token1, dec0, dec1, wethIsToken0, tickSpacing, LpShadowProperties.Pool.ETH);
    }

    public PoolInfo {
        if (asset == null || asset.isBlank()) asset = LpShadowProperties.Pool.ETH;
    }

    public boolean isEth() {
        return LpShadowProperties.Pool.ETH.equals(asset);
    }

    public boolean isStable() {
        return LpShadowProperties.Pool.STABLE.equals(asset);
    }

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
