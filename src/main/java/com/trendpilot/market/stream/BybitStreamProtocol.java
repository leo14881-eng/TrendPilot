package com.trendpilot.market.stream;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendpilot.market.Kline;

/**
 * Bybit v5 现货公共流：K 线用 kline.15.&lt;SYMBOL&gt;（只取 confirm=true 的已收盘 K 线），
 * FastWatch 用 orderbook.1.&lt;SYMBOL&gt;。每条订阅消息最多 10 个主题，需每 20 秒发 ping。
 */
final class BybitStreamProtocol implements StreamProtocol {

    private static final int BATCH = 10;
    private static final long QUARTER_MS = 15 * 60_000L;

    private final ObjectMapper json;
    private final URI uri;

    BybitStreamProtocol(ObjectMapper json, String url) {
        this.json = json;
        this.uri = URI.create(url);
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public int maxStreams() {
        return 500;
    }

    @Override
    public long sendGapMs() {
        return 100;
    }

    @Override
    public List<String> subscribe(Collection<String> klineSymbols, Collection<String> bookSymbols) {
        return messages("subscribe", klineSymbols, bookSymbols);
    }

    @Override
    public List<String> unsubscribe(Collection<String> klineSymbols, Collection<String> bookSymbols) {
        return messages("unsubscribe", klineSymbols, bookSymbols);
    }

    private List<String> messages(String op, Collection<String> klines, Collection<String> books) {
        List<String> topics = new ArrayList<>();
        klines.forEach(s -> topics.add("kline.15." + s));
        books.forEach(s -> topics.add("orderbook.1." + s));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < topics.size(); i += BATCH) {
            var node = json.createObjectNode();
            node.put("op", op);
            var args = node.putArray("args");
            topics.subList(i, Math.min(topics.size(), i + BATCH)).forEach(args::add);
            out.add(node.toString());
        }
        return out;
    }

    @Override
    public String ping() {
        return "{\"op\":\"ping\"}";
    }

    @Override
    public Event parse(String message) {
        JsonNode root;
        try {
            root = json.readTree(message);
        } catch (Exception e) {
            return null;
        }
        String topic = root.path("topic").asText("");
        if (topic.startsWith("kline.15.")) {
            Event live = null;
            for (JsonNode k : root.path("data")) {
                long open = k.path("start").asLong();
                Kline kl = new Kline(open, k.path("open").asDouble(), k.path("high").asDouble(), k.path("low").asDouble(),
                        k.path("close").asDouble(), k.path("turnover").asDouble(), open + QUARTER_MS - 1);
                String sym = topic.substring("kline.15.".length());
                if (!kl.valid()) continue; // 缺字段或价格不合理：按缺失处理，不写缓存
                if (k.path("confirm").asBoolean(false)) return new ClosedKline(sym, kl);
                if (live == null) live = new LiveKline(sym, kl);
            }
            return live;
        }
        if (topic.startsWith("orderbook.1.")) {
            JsonNode d = root.path("data");
            JsonNode b = d.path("b");
            JsonNode a = d.path("a");
            if (b.isEmpty() || a.isEmpty()) return null;
            return new BestQuote(d.path("s").asText(), b.get(0).get(0).asDouble(), a.get(0).get(0).asDouble());
        }
        return null;
    }
}
