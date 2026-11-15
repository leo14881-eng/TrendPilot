package com.trendpilot;

import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * 启动时的已知提示改记 INFO：application.yml 开了 Flyway {@code out-of-order: true}（多个分支的迁移编号可能不按顺序到达），
 * Flyway 每次启动都会在 DbMigrate 记一条 WARN "outOfOrder mode is active. Migration of schema ... may not be reproducible."
 * 这是配置本身的固定提示，不代表迁移出了问题，却让每次重启都多一条 WARN，复盘时和真正的问题混在一起。
 * <p>
 * 只改这一条（logger 名和消息开头都要对上、不带异常），原文照样记，只是级别改成 INFO；
 * DbMigrate 的其他 WARN（例如被忽略或未来版本的迁移）和迁移失败的 ERROR 不受影响。
 */
public final class KnownStartupNotice extends TurboFilter {

    static final String DB_MIGRATE = "org.flywaydb.core.internal.command.DbMigrate";
    static final String OUT_OF_ORDER = "outOfOrder mode is active";

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if (level == Level.WARN && t == null && format != null && logger != null
                && DB_MIGRATE.equals(logger.getName()) && format.startsWith(OUT_OF_ORDER)) {
            logger.info(format);
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }

    /** 装到 Logback 上（Spring Boot 初始化日志之后、Flyway 迁移之前调用）；重复调用只装一个。 */
    static void install() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) return;
        if (ctx.getTurboFilterList().stream().anyMatch(f -> f instanceof KnownStartupNotice)) return;
        KnownStartupNotice f = new KnownStartupNotice();
        f.setName("known-startup-notice");
        f.start();
        ctx.addTurboFilter(f);
    }
}
