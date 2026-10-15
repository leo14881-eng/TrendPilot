package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ListingStudyTest {

    @Test
    void krwListingNoticesAreSplitIntoTickers() throws Exception {
        String body = """
                {"success":true,"data":{"total_pages":9,"notices":[
                 {"id":5001,"title":"카이토(KAITO) 원화 마켓 디지털 자산 추가","first_listed_at":"2025-02-20T15:10:00+09:00","listed_at":"2025-02-20T15:10:00+09:00"},
                 {"id":5002,"title":"원화 마켓 디지털 자산 추가 (ARKM, PYTH)","first_listed_at":"2024-01-03T10:00:00+09:00"},
                 {"id":5003,"title":"BTC, USDT 마켓 디지털 자산 추가 (ABC)","first_listed_at":"2024-01-04T10:00:00+09:00"},
                 {"id":5004,"title":"원화 마켓 거래지원 종료 안내 (XYZ)","first_listed_at":"2024-01-05T10:00:00+09:00"}]}}
                """;
        List<ListingStudy.Event> out = new ArrayList<>();
        assertThat(ListingStudy.parseUpbit(body, out)).isEqualTo(4);
        assertThat(out).extracting(ListingStudy.Event::ticker).containsExactly("KAITO", "ARKM", "PYTH");
        assertThat(out.get(0).at().toString()).isEqualTo("2025-02-20T06:10:00Z");
    }

    @Test
    void controlAveragesSameClockTimeOnEarlierDays() {
        Map<Long, double[]> bars = new HashMap<>();
        long entry = 100L * 86_400_000L;
        for (int k = 8; k <= 30; k++) {
            long t = entry - k * 86_400_000L;
            bars.put(t, new double[]{100, 0, 0, 0, 0});
            bars.put(t + 60 * 60_000L, new double[]{102, 0, 0, 0, 0});
        }
        assertThat(ListingStudy.control(bars, entry, 60)).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ListingStudy.open(bars, entry - 8 * 86_400_000L - 2 * 60_000L)).isNotNull();
    }
}
