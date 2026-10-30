package com.trendpilot;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.importer.ClassFileImporter;
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

    /** 签名、私钥相关的类：调用交易所私有接口或链上发交易都离不开它们，影子系统一个都不该用。 */
    @ArchTest
    static final ArchRule noSigningOrPrivateKeys = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("javax.crypto.Mac")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.crypto.spec.SecretKeySpec")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.Signature")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.PrivateKey")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.KeyFactory")
            .orShould().dependOnClassesThat().resideInAnyPackage("org.web3j..", "org.bouncycastle..")
            .because("TrendPilot 只做影子模拟：不签名、不持有私钥，不调用交易所私有接口、不发链上交易");

    /** 交易所下单、账户、资金划转等私有接口路径，以及链上发交易的 RPC 方法。 */
    static final Pattern PRIVATE_API = Pattern.compile(
            "/api/v3/order|/api/v3/account|/api/v3/myTrades|/api/v3/userDataStream|/sapi/|/fapi/v\\d/(order|account|positionRisk|leverage)"
                    + "|/v5/order|/v5/account|/v5/position|/v5/asset|/v5/user|eth_sendRawTransaction|eth_sendTransaction|eth_sign|personal_");

    static List<String> privateApiHits(String text) {
        return text.lines().filter(l -> PRIVATE_API.matcher(l).find()).toList();
    }

    @Test
    void mainCodeNeverMentionsPrivateExchangeOrSigningEndpoints() throws Exception {
        try (var files = Files.walk(Path.of("src/main"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                assertThat(privateApiHits(Files.readString(f, StandardCharsets.UTF_8))).as(f.toString()).isEmpty();
            }
        }
    }

    @Test
    void theGuardsCatchViolations() {
        // 规则本身有效：签名类和私有接口路径都能被发现
        assertThatThrownBy(() -> noSigningOrPrivateKeys.check(new ClassFileImporter().importClasses(SignsRequests.class)))
                .isInstanceOf(AssertionError.class);
        assertThat(privateApiHits("http.post().uri(\"/api/v3/order?symbol={s}\")")).hasSize(1);
        assertThat(privateApiHits("call(\"/v5/order/create\")")).hasSize(1);
        assertThat(privateApiHits("Call(\"eth_sendRawTransaction\", to, data)")).hasSize(1);
        assertThat(privateApiHits("http.get().uri(\"/api/v3/klines?symbol={s}\")")).isEmpty();
        assertThat(privateApiHits("/fapi/v1/premiumIndex?symbol=")).isEmpty();
    }

    /** 只给上面的规则自检用（测试代码不在规则扫描范围内）。 */
    static final class SignsRequests {
        static byte[] sign(byte[] key, byte[] data) throws Exception {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        }
    }

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
