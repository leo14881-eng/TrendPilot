package com.trendpilot.market.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** WebSocket 推送解析：只接受已收盘 K 线；订阅消息分批。 */
class StreamProtocolTest {

    final ObjectMapper json = new ObjectMapper();
    final BinanceStreamProtocol binance = new BinanceStreamProtocol(json, "wss://stream.binance.com:9443/stream");
    final BybitStreamProtocol bybit = new BybitStreamProtocol(json, "wss://stream.bybit.com/v5/public/spot");

    @Test
    void binanceOnlyClosedKlinesAndBookTicker() {
        String open = """
                {"stream":"abcusdt@kline_15m","data":{"e":"kline","s":"ABCUSDT","k":{"t":1000,"T":900999,"o":"1","h":"2","l":"0.5","c":"1.5","q":"123","x":false}}}""";
        assertThat(binance.parse(open)).isNull();
        var closed = (StreamProtocol.ClosedKline) binance.parse(open.replace("\"x\":false", "\"x\":true"));
        assertThat(closed.symbol()).isEqualTo("ABCUSDT");
        assertThat(closed.kline().quoteVolume()).isEqualTo(123);
        assertThat(closed.kline().closeTime()).isEqualTo(900999);

        var q = (StreamProtocol.BestQuote) binance.parse("""
                {"stream":"abcusdt@bookTicker","data":{"u":1,"s":"ABCUSDT","b":"1.01","B":"5","a":"1.02","A":"7"}}""");
        assertThat(q.bid()).isEqualTo(1.01);
        assertThat(q.ask()).isEqualTo(1.02);
        assertThat(binance.parse("{\"result\":null,\"id\":1}")).isNull();
    }

    @Test
    void binanceSubscribeIsBatched() {
        List<String> symbols = java.util.stream.IntStream.range(0, 250).mapToObj(i -> "S" + i + "USDT").toList();
        List<String> msgs = binance.subscribe(symbols, List.of("ABCUSDT"));
        assertThat(msgs).hasSize(3);
        assertThat(msgs.getFirst()).contains("\"method\":\"SUBSCRIBE\"", "s0usdt@kline_15m");
        assertThat(msgs.getLast()).contains("abcusdt@bookTicker");
    }

    @Test
    void binanceNonAsciiSymbolsGetTheirOwnMessageAndParseBack() {
        List<String> msgs = binance.subscribe(List.of("ABCUSDT", "币安人生USDT", "DEFUSDT"), List.of());
        assertThat(msgs).hasSize(2);
        assertThat(msgs.getFirst()).contains("abcusdt@kline_15m", "defusdt@kline_15m").doesNotContain("币安人生");
        assertThat(msgs.getLast()).contains("币安人生usdt@kline_15m");
        var k = (StreamProtocol.ClosedKline) binance.parse("""
                {"stream":"币安人生usdt@kline_15m","data":{"s":"币安人生USDT","k":{"t":1,"T":900000,"o":"1","h":"2","l":"0.5","c":"1.5","q":"100","x":true}}}""");
        assertThat(k.symbol()).isEqualTo("币安人生USDT");
    }

    @Test
    void bybitConfirmedKlinesAndTopOfBook() {
        String k = """
                {"topic":"kline.15.ABCUSDT","data":[{"start":900000,"end":1799999,"interval":"15","open":"1","close":"1.5","high":"2","low":"0.5","volume":"10","turnover":"15","confirm":false}],"ts":1,"type":"snapshot"}""";
        assertThat(bybit.parse(k)).isNull();
        var closed = (StreamProtocol.ClosedKline) bybit.parse(k.replace("\"confirm\":false", "\"confirm\":true"));
        assertThat(closed.symbol()).isEqualTo("ABCUSDT");
        assertThat(closed.kline().openTime()).isEqualTo(900000);
        assertThat(closed.kline().closeTime()).isEqualTo(1799999);
        assertThat(closed.kline().quoteVolume()).isEqualTo(15);

        var q = (StreamProtocol.BestQuote) bybit.parse("""
                {"topic":"orderbook.1.ABCUSDT","type":"snapshot","data":{"s":"ABCUSDT","b":[["1.01","5"]],"a":[["1.02","7"]],"u":1}}""");
        assertThat(q.bid()).isEqualTo(1.01);
        assertThat(bybit.subscribe(List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"), List.of())).hasSize(2);
    }
}
