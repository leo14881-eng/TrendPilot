package com.trendpilot.lpshadow;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LP 影子盘配置（trendpilot.lpshadow）：只有公开 RPC 地址和合约地址，不需要任何密钥。
 * 池子地址不写死，启动时由 Factory.getPool 解析并校验。
 */
@ConfigurationProperties(prefix = "trendpilot.lpshadow")
public record LpShadowProperties(boolean enabled, String binanceFuturesUrl, String perpSymbol, Map<String, Chain> chains,
                                 List<Pool> pools) {

    public LpShadowProperties {
        if (binanceFuturesUrl == null || binanceFuturesUrl.isBlank()) binanceFuturesUrl = "https://fapi.binance.com";
        if (perpSymbol == null || perpSymbol.isBlank()) perpSymbol = "ETHUSDT";
        chains = chains == null ? Map.of() : Map.copyOf(chains);
        pools = pools == null ? List.of() : List.copyOf(pools);
    }

    /**
     * 一条链：公开 RPC、Uniswap V3 Factory、WETH / USDC 地址和期望的 symbol / decimals。
     * minGapMs：同一条链相邻两次请求的最小间隔。
     */
    public record Chain(String rpcUrl, String factory, String weth, String usdc, String wethSymbol, int wethDecimals,
                        String usdcSymbol, int usdcDecimals, long minGapMs) {
        public Chain {
            if (wethSymbol == null || wethSymbol.isBlank()) wethSymbol = "WETH";
            if (wethDecimals == 0) wethDecimals = 18;
            if (usdcSymbol == null || usdcSymbol.isBlank()) usdcSymbol = "USDC";
            if (usdcDecimals == 0) usdcDecimals = 6;
            if (minGapMs <= 0) minGapMs = 1_000;
        }
    }

    /** 一个池子：key 是报告和表里用的短名（如 base-005），fee 是 Uniswap 费率档（500 = 0.05%）。 */
    public record Pool(String key, String chain, int fee) {
    }
}
