package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Gated rotation research backtest (never traded live). */
class AltGateStudyTest {

    @Test
    void gateBuysOnTheNextSundayAndSellsEverythingOnTheOffDay() {
        LitStudy.Panel p = LitStudyTest.panel(200, 0.01, 0.005, -0.002, 0.003, 0.0, 0.008, -0.01);
        int from = 35, to = 190; // day 35 is a Sunday (start date is a Sunday)
        boolean[] on = new boolean[p.days];
        for (int d = 100; d <= 130; d++) on[d] = true; // day 100 is a Tuesday; first Sunday after is 105; off on day 131 (Thursday)
        Map<Integer, LitStudy.Choice> ch = AltGateStudy.gated(p, from, to, on);
        AltGateStudy.Book b = AltGateStudy.book(p, from, to, ch);
        assertThat(b.expo()[104 - from]).isZero();
        assertThat(b.expo()[105 - from]).isGreaterThan(0.99);
        assertThat(b.expo()[130 - from]).isGreaterThan(0.9);
        assertThat(b.expo()[131 - from]).isZero();
        assertThat(b.expo()[140 - from]).isZero();
        // the trade log replays exactly the same book as LitStudy.runWeekly
        double[] nav = LitStudy.runWeekly(p, from, to, 1, ch, null).nav();
        for (int j = 0; j < nav.length; j++) assertThat(b.nav()[j]).isCloseTo(nav[j], offset(1e-12));
        assertThat(b.trades()).isNotEmpty();
        assertThat(b.trades()).allSatisfy(t -> {
            assertThat(t.buy()).isGreaterThanOrEqualTo(105);
            assertThat(t.sell()).isLessThanOrEqualTo(131);
        });
        var eps = AltGateStudy.episodes(from, to, on, b);
        assertThat(eps).hasSize(1);
        assertThat(eps.getFirst().start()).isEqualTo(100);
        assertThat(eps.getFirst().end()).isEqualTo(131);
        assertThat(eps.getFirst().invested()).isTrue();
    }

    @Test
    void basketGateOpensWhenAltsBeatBtcAndCloses() {
        // alts rise faster than BTC (0.1%/day) → basket / BTC trends up and basket above its mean
        LitStudy.Panel up = LitStudyTest.panel(150, 0.01, 0.012, 0.009, 0.011);
        AltGateStudy.S2Day[] s = AltGateStudy.s2(up);
        assertThat(s[140].on()).isTrue();
        // alts fall while BTC rises → closed
        LitStudy.Panel down = LitStudyTest.panel(150, -0.01, -0.012, -0.009, -0.011);
        assertThat(AltGateStudy.s2(down)[140].on()).isFalse();
    }
}
