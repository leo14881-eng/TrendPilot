package com.trendpilot.universe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 代币化股票 / ETF 的判断（周复盘 2026-11-01 R1），线上和回测共用：
 * <ul>
 * <li>CoinGecko coin_id 含 bstock / tokenized / xstock（Binance bStocks、Bybit xStocks 的命名规则）；</li>
 * <li>或在 {@code universe/tokenized-symbols.txt} 名单里（回测没有 coin_id，只能靠名单；线上同时用名单兜底）。</li>
 * </ul>
 * 不用"交易对以 BUSDT 结尾"这类后缀规则：BNB、ARB、SHIB、TRB、BB、CKB、DGB 等正常币也以 B 结尾，会误伤。
 */
public final class TokenizedAssets {

    private static final Pattern COIN_ID = Pattern.compile("bstock|tokenized|xstock");
    private static final Set<String> KNOWN = load();

    private TokenizedAssets() {
    }

    /** coin_id 看起来是代币化股票 / ETF；null 或空 = 不能判断，返回 false（身份缺失由审核的"身份未核实"处理）。 */
    public static boolean coinIdLooksTokenized(String coinId) {
        return coinId != null && COIN_ID.matcher(coinId.toLowerCase(Locale.ROOT)).find();
    }

    /** 交易对在已知代币化股票 / ETF 名单里。 */
    public static boolean knownSymbol(String symbol) {
        return symbol != null && KNOWN.contains(symbol.toUpperCase(Locale.ROOT));
    }

    static Set<String> known() {
        return KNOWN;
    }

    private static Set<String> load() {
        Set<String> out = new HashSet<>();
        try (InputStream in = TokenizedAssets.class.getResourceAsStream("/universe/tokenized-symbols.txt")) {
            if (in == null) throw new IllegalStateException("universe/tokenized-symbols.txt missing from classpath");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                String s = line.strip();
                if (!s.isEmpty() && !s.startsWith("#")) out.add(s.toUpperCase(Locale.ROOT));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Set.copyOf(out);
    }
}
