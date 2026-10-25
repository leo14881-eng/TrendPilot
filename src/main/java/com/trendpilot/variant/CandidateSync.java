package com.trendpilot.variant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendpilot.job.AppState;
import com.trendpilot.notify.Notifier;

/**
 * 每周读取一次自动回测的候选（GitHub 公开 raw 文件 candidates.json，由每周六的 backtest-weekly 工作流生成），
 * 把通过发布前检查（status = PASS）的候选作为新的影子变体上线（受"最多 3 个活跃变体"限制），每个都带回测证据链接。
 * <ul>
 * <li>一周只请求一次，带 If-None-Match（ETag 存在 app_state），文件没变就不处理；拿不到文件或内容读不懂就什么都不做。</li>
 * <li>回测通过也不直接改 V2：先作为影子变体跑满 30 天，再由 {@link AutoPromoter} 按转正条件决定。</li>
 * </ul>
 */
@Component
public class CandidateSync {

    private static final Logger log = LoggerFactory.getLogger(CandidateSync.class);
    static final String ETAG_KEY = "optimize:candidates-etag";
    static final String SEEN_KEY = "optimize:candidates-generated";

    private final VariantRegistry registry;
    private final OptimizeProperties opt;
    private final AppState state;
    private final Notifier notifier;
    private final ObjectMapper json;
    private RestClient http;

    public CandidateSync(VariantRegistry registry, OptimizeProperties opt, AppState state, Notifier notifier, ObjectMapper json) {
        this.registry = registry;
        this.opt = opt;
        this.state = state;
        this.notifier = notifier;
        this.json = json;
    }

    void useClient(RestClient client) {
        this.http = client;
    }

    /** 每周日越南 09:23（周六的工作流跑完之后）。 */
    @Scheduled(cron = "0 23 9 * * SUN", zone = "${trendpilot.zone}")
    public void scheduled() {
        try {
            sync(Instant.now());
        } catch (RuntimeException e) {
            log.warn("本周回测候选没有读到，下周再试：{}", e.getMessage());
        }
    }

    /** @return 本次上线的变体名 */
    public List<String> sync(Instant now) {
        if (opt.candidatesUrl() == null || opt.candidatesUrl().isBlank()) return List.of();
        if (http == null) {
            http = RestClient.builder().requestFactory(com.trendpilot.health.ApiStatsConfig.requestFactory(10_000, 30_000)).build();
        }
        String etag = state.get(ETAG_KEY).orElse(null);
        ResponseEntity<String> resp = http.get().uri(opt.candidatesUrl())
                .headers(h -> {
                    if (etag != null) h.setIfNoneMatch(etag);
                })
                .retrieve().onStatus(HttpStatusCode::is4xxClientError, (rq, rs) -> { })
                .toEntity(String.class);
        if (resp.getStatusCode().value() == 304 || resp.getBody() == null || !resp.getStatusCode().is2xxSuccessful()) return List.of();
        List<String> added = ingest(resp.getBody(), now);
        String newTag = resp.getHeaders().getETag();
        if (newTag != null) state.put(ETAG_KEY, newTag);
        return added;
    }

    /** 处理 candidates.json 的内容（单独拿出来便于测试）。 */
    public List<String> ingest(String body, Instant now) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            log.warn("candidates.json 读不懂，本周不处理：{}", e.getMessage());
            return List.of();
        }
        String generated = root.path("generatedAt").asText("");
        if (!generated.isBlank() && generated.equals(state.get(SEEN_KEY).orElse(null))) return List.of();
        List<JsonNode> pass = new ArrayList<>();
        for (JsonNode c : root.path("candidates")) {
            if ("PASS".equals(c.path("status").asText())) pass.add(c);
        }
        pass.sort(Comparator.comparingDouble((JsonNode c) -> -c.path("validation").path("equity").asDouble(0)));
        List<String> added = new ArrayList<>();
        for (JsonNode c : pass) {
            try {
                Map<String, Object> m = json.convertValue(c.path("overrides"), new com.fasterxml.jackson.core.type.TypeReference<>() { });
                VariantOverrides o = VariantOverrides.parse(m);
                String evidence = c.path("evidence").asText("");
                String note = "每周回测候选 " + c.path("id").asText("?") + "：" + o.describe();
                registry.register(o, "CANDIDATES", true, evidence, note, now).ifPresent(name -> {
                    added.add(name);
                    notifier.send("optimize:new:" + name, String.format(java.util.Locale.ROOT,
                            "🧪 新影子变体 %s 上线（每周回测通过发布前检查）：%s\n先跑满 30 天，和 V2 同期对比后再决定是否转正。证据：%s",
                            name, o.describe(), evidence.isBlank() ? "见 backtest-results 分支" : evidence));
                });
            } catch (RuntimeException e) {
                log.warn("回测候选 {} 读不懂，跳过：{}", c.path("id").asText("?"), e.getMessage());
            }
        }
        if (!generated.isBlank()) state.put(SEEN_KEY, generated);
        return added;
    }
}
