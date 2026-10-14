package com.trendpilot.market.stream;

import java.net.URI;
import java.util.Collection;
import java.util.List;

import com.trendpilot.market.Kline;

/** 一个交易所的 WebSocket 协议：订阅消息怎么拼、推送消息怎么解析。 */
interface StreamProtocol {

    URI uri();

    /** 单条连接最多订阅的流数量。 */
    int maxStreams();

    /** 发送订阅消息之间的间隔，避免超过交易所消息频率限制。 */
    long sendGapMs();

    List<String> subscribe(Collection<String> klineSymbols, Collection<String> bookSymbols);

    List<String> unsubscribe(Collection<String> klineSymbols, Collection<String> bookSymbols);

    /** 需要应用层心跳时返回心跳消息，否则 null。 */
    String ping();

    /** 解析一条推送；不关心的消息返回 null。 */
    Event parse(String message);

    sealed interface Event permits ClosedKline, BestQuote {
    }

    /** 已收盘的 15m K 线。 */
    record ClosedKline(String symbol, Kline kline) implements Event {
    }

    /** 最优买卖价。 */
    record BestQuote(String symbol, double bid, double ask) implements Event {
    }
}
