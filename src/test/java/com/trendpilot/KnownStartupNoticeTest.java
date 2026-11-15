package com.trendpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.trendpilot.support.TestData;

/** Flyway out-of-order 的固定启动提示记 INFO；DbMigrate 的其他 WARN 和别的 logger 不受影响。 */
class KnownStartupNoticeTest {

    private final LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    private final Logger dbMigrate = ctx.getLogger(KnownStartupNotice.DB_MIGRATE);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach
    void attach() {
        events.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(events);
        KnownStartupNotice.install();
        KnownStartupNotice.install(); // 重复调用只装一个
    }

    @AfterEach
    void detach() {
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(events);
        ctx.getTurboFilterList().removeIf(f -> f instanceof KnownStartupNotice);
    }

    @Test
    void installsOnce() {
        assertThat(ctx.getTurboFilterList().stream().filter(f -> f instanceof KnownStartupNotice)).hasSize(1);
    }

    @Test
    void realFlywayStartupNoticeIsLoggedAsInfo() {
        String url = "jdbc:sqlite:" + TestData.dataDir().resolve("flyway.db").toAbsolutePath();
        Flyway f = Flyway.configure().dataSource(url, null, null).outOfOrder(true).locations("classpath:db/migration").load();
        f.migrate();
        f.migrate(); // 重启：没有新迁移也会提示
        List<ILoggingEvent> notices = events.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith(KnownStartupNotice.OUT_OF_ORDER)).toList();
        assertThat(notices).hasSizeGreaterThanOrEqualTo(2).allMatch(e -> e.getLevel() == Level.INFO)
                .allMatch(e -> e.getLoggerName().equals(KnownStartupNotice.DB_MIGRATE));
        assertThat(events.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN)
                && e.getLoggerName().equals(KnownStartupNotice.DB_MIGRATE));
    }

    @Test
    void otherWarningsStayWarnings() {
        dbMigrate.warn("Schema \"main\" has a version (99) that is newer than the latest available migration (45) !");
        dbMigrate.warn("outOfOrder mode is active. with an exception", new IllegalStateException("x"));
        ctx.getLogger("com.trendpilot.Other").warn("outOfOrder mode is active. Migration of schema main may not be reproducible.");
        dbMigrate.error("Migration of schema main to version 46 failed!");
        assertThat(events.list).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.WARN, Level.WARN, Level.WARN, Level.ERROR);
    }
}
