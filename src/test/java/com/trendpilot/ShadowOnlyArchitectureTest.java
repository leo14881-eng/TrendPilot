package com.trendpilot;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 硬边界：系统只做影子模拟，不允许依赖任何交易所下单 SDK。
 */
@AnalyzeClasses(packages = "com.trendpilot", importOptions = ImportOption.DoNotIncludeTests.class)
class ShadowOnlyArchitectureTest {

    @ArchTest
    static final ArchRule noExchangeTradingSdk = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.binance..",
                    "com.bybit..",
                    "io.contek..",
                    "org.knowm.xchange..",
                    "info.bitrich.xchangestream..")
            .because("TrendPilot 只做影子模拟，capital_authority 固定为 NONE_SHADOW_ONLY");

    @ArchTest
    static final ArchRule trackingOnlyObserves = noClasses().that().resideInAPackage("com.trendpilot.tracking..")
            .should().dependOnClassesThat().resideInAnyPackage("com.trendpilot.portfolio..", "com.trendpilot.capital..",
                    "com.trendpilot.monitor..", "com.trendpilot.variant..")
            .because("信号跟踪只读行情、只写跟踪表，不写正式账本、不调用资金分配");

    static final String[] STRATEGY_PACKAGES = {
            "com.trendpilot.portfolio..", "com.trendpilot.capital..", "com.trendpilot.monitor..", "com.trendpilot.variant..",
            "com.trendpilot.v3..", "com.trendpilot.btcdaily..", "com.trendpilot.observation..", "com.trendpilot.signal..",
            "com.trendpilot.tracking..", "com.trendpilot.risk..", "com.trendpilot.v2n..", "com.trendpilot.replay..",
            "com.trendpilot.universe..", "com.trendpilot.report..", "com.trendpilot.fastwatch..", "com.trendpilot.backtest.."};

    @ArchTest
    static final ArchRule btcTrendIsIndependent = noClasses().that().resideInAPackage("com.trendpilot.btctrend..")
            .should().dependOnClassesThat().resideInAnyPackage(STRATEGY_PACKAGES)
            .because("BTC 均线趋势是单独的程序和资金（用户 2026-10-23 要求和之前的功能分开），只复用行情、通知、任务记录等基础设施");

    @ArchTest
    static final ArchRule strategiesDoNotUseBtcTrend = noClasses().that().resideInAnyPackage(STRATEGY_PACKAGES)
            .should().dependOnClassesThat().resideInAPackage("com.trendpilot.btctrend..")
            .because("其他账本不计入、不读取 BTC 均线趋势的资金和统计");
}
