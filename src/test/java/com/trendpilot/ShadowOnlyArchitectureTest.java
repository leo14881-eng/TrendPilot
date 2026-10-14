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
}
