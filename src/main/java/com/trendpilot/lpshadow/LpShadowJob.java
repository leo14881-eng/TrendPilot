package com.trendpilot.lpshadow;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.trendpilot.lease.ActiveLease;
import com.trendpilot.market.BinanceClient;
import com.trendpilot.notify.Notifier;

import jakarta.annotation.PreDestroy;

/**
 * LP 影子盘的分钟任务（开关 trendpilot.lpshadow.enabled）：采集配置的 Uniswap V3 池子（ETH / BTC / 稳定币）的链上状态并写快照。
 * <ul>
 * <li>只读：只发 eth_call / eth_gasPrice、Binance 公开行情请求和（新池子上线前一次）GeckoTerminal 公开成交量查询，不建钱包、不签名、不发交易；</li>
 * <li>用自己的单线程调度（线程名 lpshadow），不占公共定时任务线程，不用组合锁，不会拖慢 Discovery / Monitor；</li>
 * <li>任何错误只记 warn，不影响其他任务；本机待命（另一台机器在运行）时跳过。</li>
 * </ul>
 */
@Component
public class LpShadowJob {

    private static final Logger log = LoggerFactory.getLogger(LpShadowJob.class);
    /** 每分钟第 7 秒运行（避开整分钟上其他任务的高峰）。 */
    static final int SECOND_OF_MINUTE = 7;

    private final LpShadowProperties props;
    private final LpCollector collector;
    private final LpEngine engine;
    private final LpReports reports;
    private final ObjectProvider<ActiveLease> lease;
    private final boolean schedulingEnabled;
    private ScheduledExecutorService executor;

    public LpShadowJob(LpShadowProperties props, LpStore store, Notifier notifier, RestClient.Builder builder,
                       ObjectProvider<BinanceClient> binance, ObjectProvider<ActiveLease> lease,
                       @Value("${trendpilot.scheduling.enabled:true}") boolean schedulingEnabled,
                       @Value("${trendpilot.zone:Asia/Ho_Chi_Minh}") String zone,
                       @Value("${trendpilot.data-dir:./data}") String dataDir,
                       org.springframework.transaction.PlatformTransactionManager tm) {
        this.props = props;
        this.lease = lease;
        this.schedulingEnabled = schedulingEnabled;
        Map<String, RpcClient> rpc = new LinkedHashMap<>();
        props.chains().forEach((name, c) -> rpc.put(name,
                new RpcClient(name, RpcClient.http(builder, c.rpcUrl()), c.minGapMs(), System::currentTimeMillis)));
        PerpQuotes perp = new PerpQuotes((String symbol) -> {
            BinanceClient b = binance.getIfAvailable();
            if (b == null) return null;
            BinanceClient.PremiumIndex p = b.premiumIndex(props.binanceFuturesUrl(), symbol);
            return new PerpQuotes.Quote(p.markPrice(), p.lastFundingRate());
        });
        this.collector = new LpCollector(props, rpc, perp, store, notifier::send, geckoVolume(builder, props.volumeUrl()));
        this.engine = new LpEngine(store, props.rebalanceGas(), new org.springframework.transaction.support.TransactionTemplate(tm),
                java.time.ZoneId.of(zone), notifier::send);
        this.reports = new LpReports(store, java.time.ZoneId.of(zone), java.nio.file.Path.of(dataDir, "reports"), notifier::send,
                notifier::sendDocument);
    }

    /**
     * 新池子规模检查用的 24 小时成交量：GeckoTerminal 公开接口（免费、只读，不需要密钥）。
     * 只在还没开始运行的池子上线前调用（每个池子一次，取不到 30 分钟后再试），不进每分钟采集。
     */
    static PoolSizeCheck.VolumeSource geckoVolume(RestClient.Builder builder, String baseUrl) {
        RestClient http = builder.clone().baseUrl(baseUrl).build();
        return (chain, address) -> {
            try {
                com.fasterxml.jackson.databind.JsonNode n = http.get().uri("/networks/{n}/pools/{a}", chain, address.toLowerCase(java.util.Locale.ROOT))
                        .header("Accept", "application/json").retrieve().body(com.fasterxml.jackson.databind.JsonNode.class);
                return parseGeckoVolume(n);
            } catch (RuntimeException e) {
                log.info("LP 影子盘：GeckoTerminal 成交量读取失败（{} {}）：{}", chain, address, e.toString());
                return null;
            }
        };
    }

    static Double parseGeckoVolume(com.fasterxml.jackson.databind.JsonNode n) {
        if (n == null) return null;
        com.fasterxml.jackson.databind.JsonNode v = n.path("data").path("attributes").path("volume_usd").path("h24");
        if (v.isMissingNode() || v.isNull()) return null;
        try {
            double d = Double.parseDouble(v.asText());
            return Double.isFinite(d) && d >= 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!props.enabled() || !schedulingEnabled || executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lpshadow");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        scheduleNext();
        log.info("LP 影子盘已启动（每分钟采集，独立线程）");
    }

    @PreDestroy
    public synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    private synchronized void scheduleNext() {
        if (executor == null || executor.isShutdown()) return;
        long now = System.currentTimeMillis();
        long next = (now / 60_000 + 1) * 60_000 + SECOND_OF_MINUTE * 1000L;
        executor.schedule(this::tick, next - now, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            ActiveLease l = lease.getIfAvailable();
            if (l == null || l.active()) run(Instant.now());
        } catch (Throwable e) {
            log.warn("LP 影子盘：本分钟出错，已跳过：{}", e.toString());
        } finally {
            scheduleNext();
        }
    }

    /** 一分钟的工作；所有异常都在这里吞掉（只记 warn）。 */
    public List<Snapshot> run(Instant now) {
        long ts = now.truncatedTo(ChronoUnit.MINUTES).getEpochSecond();
        // 先出昨天的日报（这时状态正好是昨天最后一分钟），再处理新的一分钟
        try {
            reports.maybeRun(now, engine.books());
        } catch (RuntimeException e) {
            log.warn("LP 影子盘：报告生成出错，下一分钟再试：{}", e.toString());
        }
        List<Snapshot> rows;
        try {
            rows = collector.collect(ts, now);
        } catch (RuntimeException e) {
            log.warn("LP 影子盘：本分钟采集出错，记为 gap：{}", e.toString());
            return List.of();
        }
        engine.onSnapshots(collector.activePools(), rows);
        return rows;
    }

    LpCollector collector() {
        return collector;
    }

    LpEngine engine() {
        return engine;
    }
}
