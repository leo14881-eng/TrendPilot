package com.trendpilot.lpshadow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * LP 影子盘只读、只做影子：
 * <ul>
 * <li>不依赖任何钱包 / 签名 / web3 库；</li>
 * <li>仓库源码（src/main、scripts、pom.xml）里没有私钥、助记词、签名、发交易相关的代码；</li>
 * <li>和其他账本互不依赖（只复用行情、通知、运行权等基础设施）。</li>
 * </ul>
 */
class LpShadowArchitectureTest {

    static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.trendpilot");

    static final String[] STRATEGY_PACKAGES = {
            "com.trendpilot.portfolio..", "com.trendpilot.capital..", "com.trendpilot.monitor..", "com.trendpilot.variant..",
            "com.trendpilot.v3..", "com.trendpilot.btcdaily..", "com.trendpilot.observation..", "com.trendpilot.signal..",
            "com.trendpilot.tracking..", "com.trendpilot.risk..", "com.trendpilot.v2n..", "com.trendpilot.replay..",
            "com.trendpilot.universe..", "com.trendpilot.report..", "com.trendpilot.fastwatch..", "com.trendpilot.backtest..",
            "com.trendpilot.btctrend.."};

    @Test
    void noWalletOrSigningLibraries() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("org.web3j..", "org.bouncycastle..", "org.ethereum..",
                "io.ethers..", "com.github.ethereum..", "org.hyperledger.besu..", "com.google.crypto.tink..")
                .because("LP 影子盘只读链上数据，不建钱包、不签名、不发交易").check(MAIN);
    }

    @Test
    void lpShadowIsIndependentFromOtherBooks() {
        noClasses().that().resideInAPackage("com.trendpilot.lpshadow..")
                .should().dependOnClassesThat().resideInAnyPackage(STRATEGY_PACKAGES)
                .because("LP 影子盘是单独的账本，只复用行情、通知、运行权等基础设施").check(MAIN);
        noClasses().that().resideInAnyPackage(STRATEGY_PACKAGES)
                .should().dependOnClassesThat().resideInAPackage("com.trendpilot.lpshadow..")
                .because("其他账本不读取、不计入 LP 影子盘").check(MAIN);
    }

    /** 源码里不允许出现的写法（不区分大小写）：发交易 / 签名的 RPC 方法、私钥、助记词、签名算法。 */
    static final Pattern FORBIDDEN = Pattern.compile(
            "eth_sendRawTransaction|eth_sendTransaction|eth_sign|personal_sign|eth_signTypedData|signTransaction|signMessage"
                    + "|private[_ ]?key|mnemonic|seed[_ ]?phrase|secp256k1|keystore|web3j|ethers\\.js|Credentials\\.create",
            Pattern.CASE_INSENSITIVE);

    @Test
    void sourceTreeHasNoKeyOrSigningCode() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        List<String> hits = new ArrayList<>();
        for (String dir : List.of("src/main", "scripts")) {
            Path d = root.resolve(dir);
            if (!Files.exists(d)) continue;
            try (Stream<Path> files = Files.walk(d)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) scan(root, f, hits);
            }
        }
        scan(root, root.resolve("pom.xml"), hits);
        assertThat(hits).as("源码里不能有私钥 / 签名 / 发交易相关代码").isEmpty();
    }

    @Test
    void forbiddenPatternCatchesTypicalWalletCode() {
        assertThat(FORBIDDEN.matcher("String privateKey = \"...\"").find()).isTrue();
        assertThat(FORBIDDEN.matcher("\"method\":\"eth_sendRawTransaction\"").find()).isTrue();
        assertThat(FORBIDDEN.matcher("Credentials.create(key)").find()).isTrue();
        assertThat(FORBIDDEN.matcher("eth_call eth_gasPrice").find()).isFalse();
    }

    private static void scan(Path root, Path f, List<String> hits) throws IOException {
        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".java") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".sql")
                || name.endsWith(".sh") || name.endsWith(".ps1") || name.endsWith(".cmd") || name.endsWith(".xml")
                || name.endsWith(".properties") || name.endsWith(".py") || name.endsWith(".vbs"))) return;
        List<String> lines = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (FORBIDDEN.matcher(lines.get(i)).find()) hits.add(root.relativize(f) + ":" + (i + 1) + ": " + lines.get(i).trim());
        }
    }
}
