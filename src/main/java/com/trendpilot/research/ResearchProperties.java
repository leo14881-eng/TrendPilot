package com.trendpilot.research;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 研究数据源。coingeckoApiKey 可选：填 CoinGecko Demo key 后限流更宽松（放在本机 config/application-local.yml）。
 *
 * @param rotation       每轮轮转研究的币数
 * @param topEarly       每轮额外研究的前 N 个 EARLY
 * @param topMovers      每轮额外研究的前 N 个价格异动币
 * @param minIntervalMs  无 key 时两次 CoinGecko 请求的最小间隔（免费档保守按每分钟 5 次）
 * @param demoMinIntervalMs 配了 Demo key 时的最小间隔
 * @param maxBackoffMs   429 指数退避的上限
 * @param excludedCategories 从标的全集中排除的 CoinGecko 分类（稳定币、代币化股票等）
 */
@ConfigurationProperties(prefix = "trendpilot.research")
public record ResearchProperties(boolean enabled, String coingeckoBaseUrl, String coingeckoApiKey,
                                 String defillamaBaseUrl, int rotation, int topEarly, int topMovers,
                                 long minIntervalMs, long demoMinIntervalMs, long maxBackoffMs, double lowFloatRatio, java.util.List<String> excludedCategories) {
}
