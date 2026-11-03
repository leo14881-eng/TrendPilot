package com.trendpilot.lpshadow;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * LP 影子盘配置（trendpilot.lpshadow）：只有公开 RPC 地址和合约地址，不需要任何密钥。
 * 池子地址不写死，启动时由 Factory.getPool 解析并校验。
 */
@ConfigurationProperties(prefix = "trendpilot.lpshadow")
public record LpShadowProperties(boolean enabled, String binanceFuturesUrl, String perpSymbol, Map<String, Chain> chains,
                                 List<Pool> pools, long rebalanceGas, int l1TxBytes, int l1TxCount, double minTvlUsd,
                                 double minVolume24hUsd, String volumeUrl) {

    /** 测试和旧调用用：成本参数取默认值。 */
    public LpShadowProperties(boolean enabled, String binanceFuturesUrl, String perpSymbol, Map<String, Chain> chains, List<Pool> pools) {
        this(enabled, binanceFuturesUrl, perpSymbol, chains, pools, 0, 0, 0);
    }

    public LpShadowProperties(boolean enabled, String binanceFuturesUrl, String perpSymbol, Map<String, Chain> chains, List<Pool> pools,
                              long rebalanceGas, int l1TxBytes, int l1TxCount) {
        this(enabled, binanceFuturesUrl, perpSymbol, chains, pools, rebalanceGas, l1TxBytes, l1TxCount, 0, 0, null);
    }

    @ConstructorBinding
    public LpShadowProperties {
        if (rebalanceGas <= 0) rebalanceGas = 600_000;   // decreaseLiquidity + collect + swap + mint
        if (l1TxBytes <= 0) l1TxBytes = 1_000;           // 每笔交易的典型大小（字节）
        if (l1TxCount <= 0) l1TxCount = 3;               // 一次调仓的交易笔数（撤出+领取、换币、开新仓）
        if (binanceFuturesUrl == null || binanceFuturesUrl.isBlank()) binanceFuturesUrl = "https://fapi.binance.com";
        if (perpSymbol == null || perpSymbol.isBlank()) perpSymbol = "ETHUSDT";
        if (minTvlUsd <= 0) minTvlUsd = 500_000;            // 新池子上线前的规模门槛：链上 TVL
        if (minVolume24hUsd <= 0) minVolume24hUsd = 100_000; // 和 24 小时成交量（GeckoTerminal 公开接口）
        if (volumeUrl == null || volumeUrl.isBlank()) volumeUrl = "https://api.geckoterminal.com/api/v2";
        chains = chains == null ? Map.of() : Map.copyOf(chains);
        pools = pools == null ? List.of() : List.copyOf(pools);
    }

    /**
     * 一条链：公开 RPC、Uniswap V3 Factory、WETH / USDC 地址和期望的 symbol / decimals。
     * minGapMs：同一条链相邻两次请求的最小间隔。
     */
    public record Chain(String rpcUrl, String factory, String weth, String usdc, String wethSymbol, int wethDecimals,
                        String usdcSymbol, int usdcDecimals, long minGapMs, String l1Fee) {

        /** l1Fee：L1 数据费的读法，op-stack（Base 的 GasPriceOracle）/ arbitrum（ArbGasInfo）/ 空（不估算）。 */
        public Chain(String rpcUrl, String factory, String weth, String usdc, String wethSymbol, int wethDecimals, String usdcSymbol,
                     int usdcDecimals, long minGapMs) {
            this(rpcUrl, factory, weth, usdc, wethSymbol, wethDecimals, usdcSymbol, usdcDecimals, minGapMs, null);
        }

        @ConstructorBinding
        public Chain {
            if (wethSymbol == null || wethSymbol.isBlank()) wethSymbol = "WETH";
            if (wethDecimals == 0) wethDecimals = 18;
            if (usdcSymbol == null || usdcSymbol.isBlank()) usdcSymbol = "USDC";
            if (usdcDecimals == 0) usdcDecimals = 6;
            if (minGapMs <= 0) minGapMs = 1_000;
        }
    }

    /**
     * 一个池子的永续合约代码：配置了就用配置的；ETH 池默认 perp-symbol（ETHUSDT），BTC 池默认 BTCUSDT；稳定币池没有（不对冲）。
     */
    public String perpFor(Pool p) {
        if (p.perpSymbol() != null && !p.perpSymbol().isBlank()) return p.perpSymbol();
        return switch (p.asset()) {
            case Pool.ETH -> perpSymbol;
            case Pool.STABLE -> null;
            default -> p.asset() + "USDT";
        };
    }

    /**
     * 一个池子：key 是报告和表里用的短名（如 base-005），fee 是 Uniswap 费率档（500 = 0.05%）。
     * asset：ETH（默认）/ BTC / STABLE，决定策略组合、报告分组和对冲用的永续。
     * base / baseSymbol / baseDecimals：和 USDC 配对的代币；不填就用链上配置的 WETH。baseSymbol 可以写几个，用 | 分隔。
     * sizeCheck：上线前检查规模（TVL、24 小时成交量），不够就 INACTIVE，不运行；已经开始运行的池子不再检查。
     */
    public record Pool(String key, String chain, int fee, String asset, String base, String baseSymbol, int baseDecimals,
                       String perpSymbol, boolean sizeCheck) {

        public static final String ETH = "ETH";
        public static final String BTC = "BTC";
        public static final String STABLE = "STABLE";

        public Pool(String key, String chain, int fee) {
            this(key, chain, fee, null, null, null, 0, null, false);
        }

        @ConstructorBinding
        public Pool {
            asset = asset == null || asset.isBlank() ? ETH : asset.trim().toUpperCase(java.util.Locale.ROOT);
        }

        /** 实际的 base 代币地址 / symbol / decimals（没配置时用链上的 WETH）。 */
        String baseAddress(Chain c) {
            return base == null || base.isBlank() ? c.weth() : base;
        }

        String baseSymbolOr(Chain c) {
            return base == null || base.isBlank() ? c.wethSymbol() : baseSymbol;
        }

        int baseDecimalsOr(Chain c) {
            return base == null || base.isBlank() ? c.wethDecimals() : baseDecimals;
        }
    }
}
