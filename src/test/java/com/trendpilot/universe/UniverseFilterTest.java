package com.trendpilot.universe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.support.TestData;

/** Tokenized ETFs / stocks are kept out of the universe with one shared rule for live and backtest (weekly review R1). */
class UniverseFilterTest {

    private final UniverseFilter filter = new UniverseFilter(TestData.params());

    private static SymbolInfo sym(String s) {
        return new SymbolInfo(s, s.substring(0, s.length() - 4), "USDT", true, "");
    }

    private static Ticker24h t(String s) {
        return new Ticker24h(s, 1.0, 1.0, 1e6);
    }

    @Test
    void coinIdPatternMarksTokenizedEtfsAndStocks() {
        assertThat(TokenizedAssets.coinIdLooksTokenized("direxion-semiconductor-bear-3x-etf-bstocks-tokenized-stock")).isTrue();
        assertThat(TokenizedAssets.coinIdLooksTokenized("kraneshares-star-market-50-etf-bstocks-tokenized-etf")).isTrue();
        assertThat(TokenizedAssets.coinIdLooksTokenized("micron-technology-bstock")).isTrue();
        assertThat(TokenizedAssets.coinIdLooksTokenized("nvidia-xstock")).isTrue();
        assertThat(TokenizedAssets.coinIdLooksTokenized("spacex-xstocks")).isTrue();
        // ordinary coins whose symbols happen to end in B are not affected
        for (String id : List.of("binancecoin", "arbitrum", "shiba-inu", "tellor", "bouncebit", "nervos-network", "digibyte",
                "initia", "beam-2", "tokenize-xchange")) {
            assertThat(TokenizedAssets.coinIdLooksTokenized(id)).as(id).isFalse();
        }
        assertThat(TokenizedAssets.coinIdLooksTokenized(null)).isFalse();
    }

    @Test
    void knownTokenizedSymbolsAreExcludedEvenWithoutCategoryOrStaticEntry() {
        // SOXSB is neither in the CoinGecko category nor in the static base-asset list: the review's leak
        List<SymbolInfo> symbols = List.of(sym("BTCUSDT"), sym("SOXSBUSDT"), sym("SNXXBUSDT"), sym("KORUBUSDT"), sym("INITUSDT"),
                sym("BNBUSDT"), sym("ARBUSDT"));
        List<Ticker24h> tickers = symbols.stream().map(s -> t(s.symbol())).toList();
        List<String> picked = filter.select(symbols, tickers, Set.of(), Set.of()).stream().map(Ticker24h::symbol).toList();
        assertThat(picked).containsExactly("BTCUSDT", "INITUSDT", "BNBUSDT", "ARBUSDT");
    }

    @Test
    void backtestUsesTheSameRule() {
        var u = TestData.params().universe();
        assertThat(UniverseFilter.excludedOffline(u, "SOXSBUSDT")).isTrue();
        assertThat(UniverseFilter.excludedOffline(u, "UVXYBUSDT")).isTrue();
        assertThat(UniverseFilter.excludedOffline(u, "GMEBUSDT")).isTrue();   // static list
        assertThat(UniverseFilter.excludedOffline(u, "USDCUSDT")).isTrue();   // stablecoin
        assertThat(UniverseFilter.excludedOffline(u, "INITUSDT")).isFalse();
        assertThat(UniverseFilter.excludedOffline(u, "BNBUSDT")).isFalse();
        assertThat(UniverseFilter.excludedOffline(u, "BBUSDT")).isFalse();
    }
}
