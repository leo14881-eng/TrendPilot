package com.trendpilot.variant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 线上 application.yml 的变体配置：每个还在跑的变体都在 FastWatch 名单里，评估起点和影子对账的默认值正确。
 * 直接读打包进去的 application.yml（不改测试配置），新加变体时这里会挡住没接 FastWatch 的。
 */
class VariantConfigTest {

    private static Binder binder() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        return Binder.get(env);
    }

    @Test
    void everyRunningConfiguredVariantIsOnFastWatch() throws Exception {
        VariantsProperties vp = binder().bind("trendpilot.variants", VariantsProperties.class).get();
        assertThat(vp.list()).isNotEmpty();
        for (VariantsProperties.Def d : vp.list()) {
            if (d.isRetired()) continue;
            VariantOverrides o = VariantRegistry.overridesOf(d);
            assertThat(VariantRegistry.fastWatchCovers(o)).as(d.name() + " 必须接入 FastWatch").isTrue();
        }
    }

    @Test
    void evaluationStartsAtTheFastWatchDeployAndNothingIsReconciledYet() throws Exception {
        OptimizeProperties opt = binder().bind("trendpilot.optimize", OptimizeProperties.class).get();
        assertThat(opt.fastwatchSinceInstant()).isEqualTo(Instant.parse("2026-11-02T02:32:17Z"));
        for (String v : new String[]{"V5", "V6", "V6B", "V6BS6"}) {
            assertThat(opt.reconciliation(v).ok()).as(v).isFalse();
            assertThat(opt.reconciliation(v).executionGapPerK()).as(v).isNull();
        }
        // V5 买法按新的执行敏感度只是 PROMISING：不再标记为回测通过
        VariantsProperties vp = binder().bind("trendpilot.variants", VariantsProperties.class).get();
        assertThat(vp.list()).filteredOn(d -> d.name().equals("V5")).singleElement().satisfies(d -> assertThat(d.backtestPassed()).isFalse());
    }
}
