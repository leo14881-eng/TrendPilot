package com.trendpilot.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.ObservationBook;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.support.TestData;

/** 每日汇总：正文只列 V2、不超过一条 Telegram；V1 全部持仓和被套列表只在附件明细里。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DailyDigestTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired HealthJob health;
    @Autowired ShadowBook book;
    @Autowired ObservationBook v1;

    @Test
    void briefListsOnlyV2AndFitsOneMessage() {
        Instant t = Instant.parse("2026-10-19T10:00:00Z");
        Fill buy = new Fill(new BigDecimal("10"), new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("1"), 0, true);
        Position p = book.open(Venue.BINANCE, "DIGV2USDT", buy, "G", t);
        book.update(p, new BigDecimal("-30"), t);
        for (int i = 0; i < 500; i++) {
            Position o = v1.open(Venue.BINANCE, "DIGV1X" + i + "USDT", buy, "G", t);
            v1.update(o, new BigDecimal("-5"), t);
        }
        HealthJob.Digest d = health.digest(t.plusSeconds(3600));

        assertThat(d.brief().length()).isLessThanOrEqualTo(HealthJob.BRIEF_MAX);
        assertThat(d.brief()).contains("DIGV2USDT", "V2 被套持仓", "V1：买入", "见附件");
        assertThat(d.brief()).doesNotContain("DIGV1X");
        assertThat(d.full()).contains("DIGV2USDT", "DIGV1X0USDT", "DIGV1X499USDT", "## V1 全部持仓", "### V1");
    }

    @Test
    void capCutsAtLineAndNotesAttachment() {
        String longText = ("x".repeat(99) + "\n").repeat(100);
        String c = HealthJob.cap(longText);
        assertThat(c.length()).isLessThanOrEqualTo(HealthJob.BRIEF_MAX);
        assertThat(c).endsWith("完整内容见附件");
        assertThat(HealthJob.cap("短")).isEqualTo("短");
    }
}
