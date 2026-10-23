package com.trendpilot.btctrend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.trendpilot.job.JobRuns;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Venue;
import com.trendpilot.notify.Notifier;

/** 取不到 Bybit 日线：不动作、不碰账本，当天告警一次。 */
class BtcTrendJobTest {

    @Test
    void noBybitDataMeansNoActionAndOneAlert() {
        Markets markets = mock(Markets.class);
        when(markets.get(Venue.BYBIT)).thenReturn(null);
        BtcTrendService service = mock(BtcTrendService.class);
        Notifier notifier = mock(Notifier.class);
        BtcTrendJob job = new BtcTrendJob(markets, service, notifier, mock(JobRuns.class), true);
        BtcTrendService.Outcome o = job.run(Instant.parse("2026-10-23T00:05:00Z"));
        assertThat(o.status()).isEqualTo(BtcTrendService.Status.NO_DATA);
        verify(service, never()).run(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any());
        verify(notifier).send(eq("btctrend:nodata:2026-10-23"), contains("暂不动作"));
        verify(notifier, never()).send(contains("btctrend:daily"), anyString());
    }
}
