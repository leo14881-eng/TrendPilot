package com.trendpilot.report;

/** 测试用：暴露包内的 Telegram 转换。 */
public final class HoldingsReportTestAccess {
    private HoldingsReportTestAccess() {}

    public static String telegram(String md) {
        return HoldingsReport.telegram(md);
    }
}
