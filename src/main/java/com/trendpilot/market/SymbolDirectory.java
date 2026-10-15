package com.trendpilot.market;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * 交易对信息缓存：整表一次请求，缓存 30 分钟，Discovery 和 Monitor 共用。
 * 不在表里的标的（已下架）返回 null。每个交易所一个实例（见 {@link Markets}）。
 * <p>
 * 防误判：交易所返回空表、或比上一份少 20% 以上（维护、接口格式变化、半截响应），视为请求失败，
 * 不替换缓存——否则一份坏数据会让所有持仓看起来都"下架"。失败结果记 30 秒，同一轮不重复请求。
 * 刷新失败时，6 小时内最近一次正常的列表继续可用（交易状态很少变化，下架会提前公告），
 * 免得一次接口失败让所有持仓都因"交易状态拿不到"失去管理；超过 6 小时才报错。
 */
public class SymbolDirectory {

    static final Duration TTL = Duration.ofMinutes(30);
    static final Duration ERROR_TTL = Duration.ofSeconds(30);
    static final double MIN_RATIO = 0.8;
    static final Duration STALE_MAX = Duration.ofHours(6);
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SymbolDirectory.class);

    private final MarketDataSource market;
    private final Clock clock;
    private volatile Snapshot cached;
    private Snapshot lastGood;
    private RuntimeException error;
    private Instant errorAt;

    private record Snapshot(List<SymbolInfo> all, Map<String, SymbolInfo> bySymbol, Instant fetchedAt) {
    }

    public SymbolDirectory(MarketDataSource market) {
        this(market, Clock.systemUTC());
    }

    SymbolDirectory(MarketDataSource market, Clock clock) {
        this.market = market;
        this.clock = clock;
    }

    public List<SymbolInfo> all() {
        return snapshot().all();
    }

    public SymbolInfo get(String symbol) {
        return snapshot().bySymbol().get(symbol);
    }

    /** 丢弃缓存和"上一份"基准，下次使用时重新请求（只在测试里换市场数据时用）。 */
    public synchronized void invalidate() {
        cached = null;
        lastGood = null;
        error = null;
    }

    private synchronized Snapshot snapshot() {
        Instant now = clock.instant();
        if (cached == null || Duration.between(cached.fetchedAt(), now).compareTo(TTL) > 0) {
            if (error != null && Duration.between(errorAt, now).compareTo(ERROR_TTL) < 0) {
                if (usable(lastGood, now)) return lastGood;
                throw error;
            }
            try {
                List<SymbolInfo> all = market.symbols().stream()
                        .filter(i -> i.symbol() != null && !i.symbol().isBlank()).toList();
                if (all.isEmpty()) throw new MarketDataException("交易对列表为空");
                if (lastGood != null && all.size() < lastGood.all().size() * MIN_RATIO) {
                    throw new MarketDataException("交易对列表从 " + lastGood.all().size() + " 个骤降到 " + all.size()
                            + " 个，视为异常数据");
                }
                cached = new Snapshot(all, all.stream().collect(Collectors.toMap(SymbolInfo::symbol, Function.identity(),
                        (a, b) -> a)), now);
                lastGood = cached;
                error = null;
            } catch (RuntimeException e) {
                cached = null;
                error = e;
                errorAt = now;
                if (usable(lastGood, now)) {
                    log.warn("交易对列表刷新失败，继续用 {} 的列表：{}", lastGood.fetchedAt(), e.getMessage());
                    return lastGood;
                }
                throw e;
            }
        }
        return cached;
    }

    private static boolean usable(Snapshot s, Instant now) {
        return s != null && Duration.between(s.fetchedAt(), now).compareTo(STALE_MAX) <= 0;
    }
}
