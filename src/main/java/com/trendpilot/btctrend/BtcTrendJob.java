package com.trendpilot.btctrend;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.job.JobRuns;
import com.trendpilot.market.Kline;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.notify.Notifier;

/**
 * BTC 均线趋势的定时任务（独立开关 trendpilot.btc-trend.enabled）。每小时 :05（UTC）运行一次：
 * 取 Bybit BTC/USDT 现货日线（1 次请求，走统一限流），每天第一次拿到昨天收盘的日线时（正常是 UTC 00:05 = 越南 07:05）
 * 判断并影子成交、推送当天状态；之后同一天的运行只更新现价，不重复成交。取不到数据或数据过期：不动作，当天告警一次。
 * 只在持有运行权的实例上执行（待命实例的定时任务整体跳过）。
 */
@Component
public class BtcTrendJob {

    private static final Logger log = LoggerFactory.getLogger(BtcTrendJob.class);
    static final String JOB = "BTC_TREND";
    static final String SYMBOL = "BTCUSDT";
    static final int BARS = 400;

    private final Markets markets;
    private final BtcTrendService service;
    private final Notifier notifier;
    private final JobRuns runs;
    private final boolean enabled;

    public BtcTrendJob(Markets markets, BtcTrendService service, Notifier notifier, JobRuns runs,
                       @Value("${trendpilot.btc-trend.enabled:true}") boolean enabled) {
        this.markets = markets;
        this.service = service;
        this.notifier = notifier;
        this.runs = runs;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    public void scheduled() {
        if (enabled) run(Instant.now());
    }

    public BtcTrendService.Outcome run(Instant now) {
        Instant started = Instant.now();
        String today = now.atOffset(ZoneOffset.UTC).toLocalDate().toString();
        List<Kline> raw;
        try {
            VenueMarket m = markets.get(Venue.BYBIT);
            if (m == null) throw new IllegalStateException("Bybit 行情未启用");
            raw = m.source().klines(SYMBOL, "1d", null, BARS);
        } catch (RuntimeException e) {
            log.warn("BTC 均线趋势：取不到 Bybit 日线，本次不动作：{}", e.toString());
            alert(today, "取不到 Bybit BTC/USDT 日线");
            runs.record(JOB, "BT-" + now, started, Instant.now(), "SUCCESS", "NO_DATA", e.getClass().getSimpleName());
            return new BtcTrendService.Outcome(BtcTrendService.Status.NO_DATA, null, List.of(), null);
        }
        BtcTrendService.Outcome o = service.run(raw, now);
        switch (o.status()) {
            case DONE -> {
                notifier.send("btctrend:daily:" + o.signalDay(), o.daily());
                for (String a : o.actions()) notifier.send("btctrend:trade:" + o.signalDay() + ":" + a.split(" ")[0],
                        "📈 BTC 均线趋势（影子）成交\n" + a);
                runs.record(JOB, "BT-" + o.signalDay(), started, Instant.now(), "SUCCESS", "COMPLETE",
                        o.actions().isEmpty() ? "no action" : String.join("; ", o.actions()));
                log.info("BTC 均线趋势：信号日 {} 处理完成，动作 {}", o.signalDay(), o.actions());
            }
            case NO_DATA, STALE -> {
                log.warn("BTC 均线趋势：最新已收盘日线 {} 不是昨天，本次不动作", o.signalDay());
                alert(today, "Bybit 最新已收盘日线是 " + o.signalDay() + "，不是昨天");
                runs.record(JOB, "BT-" + now, started, Instant.now(), "SUCCESS", o.status().name(), o.signalDay());
            }
            case ALREADY -> { }
        }
        return o;
    }

    private void alert(String today, String why) {
        // 每小时会重试；同一天只提醒一次
        notifier.send("btctrend:nodata:" + today, "⚠️ BTC 均线趋势：" + why + "，今天暂不动作，之后每小时自动重试。");
    }
}
