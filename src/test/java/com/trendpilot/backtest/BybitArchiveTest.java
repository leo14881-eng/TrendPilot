package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;

import org.junit.jupiter.api.Test;

/** Bybit trades are aggregated into 15m candles in the Binance kline layout that Archive reads. */
class BybitArchiveTest {

    @Test
    void tradesBecomeFifteenMinuteCandlesInBinanceLayout() throws Exception {
        long t0 = 1_793_491_200_000L; // 2026-11-01T00:00Z
        BybitArchive.Bars b = new BybitArchive.Bars();
        b.add(new BufferedReader(new StringReader("id,timestamp,price,volume,side,rpi\n"
                + "2," + (t0 + 2000) + ",101,2,sell,0\n"
                + "1," + (t0 + 1000) + ",100,1,buy,0\n"            // earlier trade listed later: still the open
                + "3," + (t0 + 60_000) + ",103,1,buy,0\n"
                + "4," + (t0 + 15 * 60_000) + ",99,4,sell,0\n")));
        // older format: seconds with decimals, column named size
        b.add(new BufferedReader(new StringReader("timestamp,symbol,side,size,price\n" + ((t0 + 16 * 60_000) / 1000.0) + ",X,Buy,1,98\n")));
        String csv = b.csv();
        String[] lines = csv.split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).isEqualTo(t0 + ",100,103,100,103,4," + (t0 + Series.Q - 1) + ",405,3,2,203,0");
        assertThat(lines[1]).startsWith((t0 + Series.Q) + ",99,99,98,98,5,");

        Series s = new Series("XUSDT", t0, 4);
        assertThat(Archive.parse(new BufferedReader(new StringReader(csv)), s, Series.Q)).isTrue();
        assertThat(s.close[0]).isEqualTo(103f);
        assertThat(s.close[1]).isEqualTo(98f);
    }

    @Test
    void readsHrefsFromTheDirectoryListing() {
        assertThat(BybitArchive.hrefs("<li><a href=\"0GUSDT/\">0GUSDT</a></li><li><a href=\"BTCUSDT/\">x</a>"))
                .containsExactly("0GUSDT/", "BTCUSDT/");
    }
}
