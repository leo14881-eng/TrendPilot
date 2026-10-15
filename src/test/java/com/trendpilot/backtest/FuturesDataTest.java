package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FuturesDataTest {

    @TempDir
    Path dir;

    private Path zip(String name, String body) throws Exception {
        Path p = dir.resolve(name);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            z.putNextEntry(new ZipEntry("x.csv"));
            z.write(body.getBytes(StandardCharsets.US_ASCII));
            z.closeEntry();
        }
        return p;
    }

    @Test
    void perpNameFallsBackToThousandContracts() {
        Set<String> perps = Set.of("ABCUSDT", "1000PEPEUSDT");
        assertThat(FuturesData.perpFor("ABCUSDT", perps)).isEqualTo("ABCUSDT");
        assertThat(FuturesData.perpFor("PEPEUSDT", perps)).isEqualTo("1000PEPEUSDT");
        assertThat(FuturesData.perpFor("XYZUSDT", perps)).isNull();
    }

    @Test
    void fundingIsReadInPercentAndFirstRecordMarksListing() throws Exception {
        FuturesData fd = new FuturesData(dir, 0, 24 * 30);
        FuturesData.Fut f = new FuturesData.Fut("ABCUSDT", 24 * 30);
        long t = 10L * 24 * Series.H; // 第 10 天 0 点第一次结算，结算周期 4 小时
        fd.readFunding(zip("f.zip", "calc_time,funding_interval_hours,last_funding_rate\n" + t + ",4,-0.00125\n" + (t + 4 * Series.H) + ",4,0.0001\n"), f);
        assertThat(f.funding[240]).isEqualTo(-0.125f);
        assertThat(f.funding[244]).isEqualTo(0.01f);
        assertThat(f.listedHour()).isEqualTo(236);
        FuturesData.fillForward(f.funding, 9);
        assertThat(f.funding[243]).isEqualTo(-0.125f);
        assertThat(f.funding[253]).isEqualTo(0.01f);
        assertThat(f.funding[254]).isNaN();
    }

    @Test
    void metricsKeepTheLastValueOfEachHour() throws Exception {
        FuturesData fd = new FuturesData(dir, 0, 48);
        FuturesData.Fut f = new FuturesData.Fut("ABCUSDT", 48);
        String head = "create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio\n";
        fd.readMetrics(zip("m.zip", head + "1970-01-01 01:05:00,ABCUSDT,1,100,1.1,1.2,0.9,1.5\n1970-01-01 01:55:00,ABCUSDT,1,200,1.1,1.3,0.8,1.6\n"), f);
        assertThat(f.oiValue[1]).isEqualTo(200f);
        assertThat(f.topLs[1]).isEqualTo(1.3f);
        assertThat(f.ls[1]).isEqualTo(0.8f);
        assertThat(f.takerLs[1]).isEqualTo(1.6f);
        assertThat(f.oiValue[0]).isNaN();
    }

    @Test
    void spotTakerBuyVolumeIsParsed() throws Exception {
        Series s = new Series("ABCUSDT", 0, 4);
        String csv = "0,1,1,1,1,10,899,100,5,6,60,0\n" + Series.Q + ",1,1,1,1,10,1799,100,5,6,40,0\n";
        assertThat(Archive.parse(new BufferedReader(new StringReader(csv)), s, Series.Q)).isTrue();
        assertThat(s.takerBuyQuote[0]).isEqualTo(60f);
        assertThat(s.takerBuyShare(1, 2)).isEqualTo(0.5);
        assertThat(s.firstIndex()).isZero();
    }

    @Test
    void derivSignsReadTheHourOfTheBar() {
        int hours = 48;
        FuturesData fd = new FuturesData(dir, 0, hours);
        FuturesData.Fut f = new FuturesData.Fut("ABCUSDT", hours);
        f.oiValue[10] = 100;
        f.oiValue[34] = 125;
        f.funding[34] = -0.2f;
        fd.put("ABCUSDT", f);
        Series s = new Series("ABCUSDT", 0, hours * 4);
        for (int i = 0; i < hours * 4; i++) s.set(i, 1, 1, 1, 1, 10);
        int q = 34 * 4 + 3; // 第 34 小时最后一根 15 分钟
        assertThat(DerivSigns.oiChange(fd, s, q, 24)).isCloseTo(25.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(DerivSigns.funding(fd, s, q)).isCloseTo(-0.2, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(DerivSigns.signs(fd).get(4).cond().hit(s, q, null)).isTrue();
    }
}
