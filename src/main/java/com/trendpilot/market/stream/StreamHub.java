package com.trendpilot.market.stream;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendpilot.market.KlineStore;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Venue;

import jakarta.annotation.PreDestroy;

/**
 * WebSocket 行情：
 * <ul>
 * <li>K 线缓存采集：订阅扫描范围的 15m K 线，已收盘的 K 线写入 kline_15m。之后 Discovery / Monitor 读取时
 *     发现缓存已有，就不再走 REST；缺口、断线期间的 K 线仍由 REST 补齐，过期或不连续的数据会被拒绝。</li>
 * <li>FastWatch 最优价：订阅持仓的最优买卖价（Binance bookTicker、Bybit orderbook.1），只供 FastWatch 观察。</li>
 * </ul>
 * 不是每来一条消息就买卖；正式决策仍按定时任务的同一时间截面。
 */
@Component
public class StreamHub {

    private static final Logger log = LoggerFactory.getLogger(StreamHub.class);

    /** 最优买卖价及收到时间。 */
    public record Quote(double bid, double ask, Instant at) {
    }

    private final StreamProperties props;
    private final KlineStore store;
    private final Markets markets;
    private final ObjectMapper json;
    private final Map<Venue, VenueStream> streams = new EnumMap<>(Venue.class);
    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private final RawFrameLog raw;

    public StreamHub(StreamProperties props, KlineStore store, Markets markets, ObjectMapper json) {
        this.props = props;
        this.store = store;
        this.markets = markets;
        this.json = json;
        this.raw = new RawFrameLog(props.debugLog(), java.nio.file.Path.of("logs", "ws-raw.log"));
        if (raw.enabled()) log.info("WebSocket 原始消息日志已开启：logs/ws-raw.log");
    }

    /** 设定某交易所需要采集 K 线的标的（Discovery 每轮按扫描范围调用）。 */
    public synchronized void watchKlines(Venue venue, Set<String> symbols) {
        VenueStream s = stream(venue);
        if (s != null) s.update(symbols, null);
    }

    /** 设定某交易所需要最优价的标的（FastWatch 按持仓调用）。 */
    public synchronized void watchBooks(Venue venue, Set<String> symbols) {
        VenueStream s = stream(venue);
        if (s != null) s.update(null, symbols);
    }

    /** 最近的最优价；超过 maxAgeSeconds 或没有时返回 null，不拿旧价格冒充新鲜。 */
    public Quote quote(Venue venue, String symbol, long maxAgeSeconds) {
        Quote q = quotes.get(venue + ":" + symbol);
        if (q == null || Instant.now().getEpochSecond() - q.at().getEpochSecond() > maxAgeSeconds) return null;
        return q;
    }

    /** 每个交易所连接是否健康（90 秒内有消息）。 */
    public synchronized Map<Venue, Boolean> health() {
        Map<Venue, Boolean> out = new LinkedHashMap<>();
        streams.forEach((v, s) -> out.put(v, s.connection.healthy()));
        return out;
    }

    private VenueStream stream(Venue venue) {
        if (!props.enabled() || markets.get(venue) == null) return null;
        return streams.computeIfAbsent(venue, v -> {
            if (scheduler == null) {
                scheduler = StreamConnection.newScheduler();
            }
            StreamProtocol protocol = v == Venue.BINANCE
                    ? new BinanceStreamProtocol(json, props.binanceUrl())
                    : new BybitStreamProtocol(json, props.bybitUrl());
            VenueStream s = new VenueStream(v, protocol);
            s.start();
            return s;
        });
    }

    @PreDestroy
    public synchronized void stop() {
        streams.values().forEach(s -> s.connection.close());
        if (scheduler != null) scheduler.shutdownNow();
    }

    /** 一个交易所的订阅状态和连接。 */
    private final class VenueStream {
        private final Venue venue;
        private final StreamProtocol protocol;
        private final StreamConnection connection;
        private Set<String> klines = new HashSet<>();
        private Set<String> books = new HashSet<>();

        VenueStream(Venue venue, StreamProtocol protocol) {
            this.venue = venue;
            this.protocol = protocol;
            this.connection = new StreamConnection(venue + " stream", protocol.uri(), this::onMessage,
                    c -> resubscribeAll(), scheduler);
        }

        void start() {
            connection.connect();
            if (protocol.ping() != null) {
                scheduler.scheduleAtFixedRate(() -> connection.send(protocol.ping()), 20, 20, TimeUnit.SECONDS);
            }
            // 交易所会在 24 小时左右强制断开，提前主动重连
            scheduler.scheduleAtFixedRate(connection::reconnect, 23, 23, TimeUnit.HOURS);
        }

        synchronized void update(Set<String> newKlines, Set<String> newBooks) {
            Set<String> k = newKlines == null ? klines : limit(newKlines, protocol.maxStreams() - books.size());
            Set<String> b = newBooks == null ? books : new HashSet<>(newBooks);
            List<String> addK = diff(k, klines), delK = diff(klines, k);
            List<String> addB = diff(b, books), delB = diff(books, b);
            klines = k;
            books = b;
            List<String> msgs = new ArrayList<>(protocol.unsubscribe(delK, delB));
            msgs.addAll(protocol.subscribe(addK, addB));
            connection.sendPaced(msgs, protocol.sendGapMs());
        }

        private synchronized void resubscribeAll() {
            connection.sendPaced(protocol.subscribe(klines, books), protocol.sendGapMs());
        }

        private Set<String> limit(Set<String> symbols, int max) {
            if (symbols.size() <= max) return new HashSet<>(symbols);
            log.warn("{} K 线订阅数 {} 超过单连接上限 {}，超出部分由 REST 获取", venue, symbols.size(), max);
            return new HashSet<>(symbols.stream().sorted().limit(Math.max(0, max)).toList());
        }

        /** 交易所推来的每一条原始消息都从这里进来（排查时打开 trendpilot.stream.debug-log）。 */
        private void onMessage(String message) {
            if (RawFrameLog.subscriptionError(message)) {
                log.warn("{} WebSocket 订阅出错：{}", venue,
                        message.length() > RawFrameLog.MAX_CHARS ? message.substring(0, RawFrameLog.MAX_CHARS) : message);
            }
            StreamProtocol.Event e = protocol.parse(message);
            raw.record(venue.name(), message, e instanceof StreamProtocol.BestQuote);
            if (e instanceof StreamProtocol.ClosedKline k) {
                store.upsert(venue, k.symbol(), List.of(k.kline()));
            } else if (e instanceof StreamProtocol.BestQuote q) {
                quotes.put(venue + ":" + q.symbol(), new Quote(q.bid(), q.ask(), Instant.now()));
            }
        }
    }

    private static List<String> diff(Set<String> a, Set<String> b) {
        return a.stream().filter(x -> !b.contains(x)).sorted().toList();
    }
}
