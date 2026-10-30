package com.trendpilot.market.stream;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendpilot.market.Kline;

/**
 * Binance 现货组合流：K 线用 &lt;symbol&gt;@kline_15m（只取 x=true 的已收盘 K 线），
 * FastWatch 用 &lt;symbol&gt;@bookTicker。单连接最多 1024 个流，每秒最多 5 条消息。
 */
final class BinanceStreamProtocol implements StreamProtocol {

    private static final int BATCH = 100;

    private final ObjectMapper json;
    private final URI uri;
    private final AtomicInteger ids = new AtomicInteger();

    BinanceStreamProtocol(ObjectMapper json, String url) {
        this.json = json;
        this.uri = URI.create(url);
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public int maxStreams() {
        return 1000;
    }

    @Override
    public long sendGapMs() {
        return 300;
    }

    @Override
    public List<String> subscribe(Collection<String> klineSymbols, Collection<String> bookSymbols) {
        return messages("SUBSCRIBE", klineSymbols, bookSymbols);
    }

    @Override
    public List<String> unsubscribe(Collection<String> klineSymbols, Collection<String> bookSymbols) {
        return messages("UNSUBSCRIBE", klineSymbols, bookSymbols);
    }

    private List<String> messages(String method, Collection<String> klines, Collection<String> books) {
        List<String> streams = new ArrayList<>();
        klines.forEach(s -> streams.add(s.toLowerCase(Locale.ROOT) + "@kline_15m"));
        books.forEach(s -> streams.add(s.toLowerCase(Locale.ROOT) + "@bookTicker"));
        // 中文名等非 ASCII 交易对（例如 币安人生USDT）单独一条消息：万一交易所拒绝这个流名，不连累同批的其他币
        List<String> ascii = streams.stream().filter(BinanceStreamProtocol::isAscii).toList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ascii.size(); i += BATCH) {
            out.add(message(method, ascii.subList(i, Math.min(ascii.size(), i + BATCH))));
        }
        streams.stream().filter(st -> !isAscii(st)).forEach(st -> out.add(message(method, List.of(st))));
        return out;
    }

    private String message(String method, List<String> streams) {
        var node = json.createObjectNode();
        node.put("method", method);
        var params = node.putArray("params");
        streams.forEach(params::add);
        node.put("id", ids.incrementAndGet());
        return node.toString();
    }

    static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }

    @Override
    public String ping() {
        return null; // Binance 用协议层 ping/pong
    }

    @Override
    public Event parse(String message) {
        JsonNode root;
        try {
            root = json.readTree(message);
        } catch (Exception e) {
            return null;
        }
        String stream = root.path("stream").asText("");
        JsonNode d = root.path("data");
        if (stream.endsWith("@kline_15m")) {
            JsonNode k = d.path("k");
            Kline kl = new Kline(k.path("t").asLong(), k.path("o").asDouble(),
                    k.path("h").asDouble(), k.path("l").asDouble(), k.path("c").asDouble(), k.path("q").asDouble(),
                    k.path("T").asLong());
            if (!kl.valid()) return null; // 缺字段或价格不合理：按缺失处理，不写缓存
            return k.path("x").asBoolean(false) ? new ClosedKline(d.path("s").asText(), kl) : new LiveKline(d.path("s").asText(), kl);
        }
        if (stream.endsWith("@bookTicker")) {
            return new BestQuote(d.path("s").asText(), d.path("b").asDouble(), d.path("a").asDouble());
        }
        return null;
    }
}
