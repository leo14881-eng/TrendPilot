package com.trendpilot.universe;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Ticker24h;

/**
 * 标的全集：正在交易、允许现货、USDT 计价，排除稳定币、法币、黄金代币和人工黑名单。
 * 不设最低市值、成交额或数量门槛，但价格和成交额必须有效。
 */
@Component
public class UniverseFilter {

    public static final String BTC = "BTCUSDT";

    private final StrategyParams params;

    public UniverseFilter(StrategyParams params) {
        this.params = params;
    }

    public List<Ticker24h> select(List<SymbolInfo> symbols, List<Ticker24h> tickers) {
        StrategyParams.Universe u = params.universe();
        Map<String, Ticker24h> bySymbol = tickers.stream()
                .collect(Collectors.toMap(Ticker24h::symbol, Function.identity(), (a, b) -> a));
        return symbols.stream()
                .filter(SymbolInfo::trading)
                .filter(s -> u.quoteAsset().equals(s.quoteAsset()))
                .filter(s -> !u.excludedBaseAssets().contains(s.baseAsset()))
                .filter(s -> !u.blocklist().contains(s.symbol()))
                .map(s -> bySymbol.get(s.symbol()))
                .filter(t -> t != null && t.lastPrice() > 0 && t.quoteVolume() > 0)
                .toList();
    }
}
