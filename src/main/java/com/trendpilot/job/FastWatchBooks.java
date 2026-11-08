package com.trendpilot.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.trendpilot.observation.DepthVariantTracker;
import com.trendpilot.portfolio.DepthVariantBook;
import com.trendpilot.portfolio.Position;
import com.trendpilot.variant.VariantTracker;

/**
 * FastWatch 除 V2 以外要看的账本（用户 2026-11-02 批准）：V2D15 和没归档的配置化影子变体。
 * 启用"让赢家跑"的变体利润保护底线算法不同，不放进来（仍由 5 分钟检查管理）。读不到的账本本轮跳过。
 */
@Component
public class FastWatchBooks {

    private static final Logger log = LoggerFactory.getLogger(FastWatchBooks.class);

    private final DepthVariantTracker depthVariant;
    private final VariantTracker variants;

    public FastWatchBooks(DepthVariantTracker depthVariant, VariantTracker variants) {
        this.depthVariant = depthVariant;
        this.variants = variants;
    }

    /** 账本名 → 当前持仓。 */
    public Map<String, List<Position>> positions() {
        Map<String, List<Position>> out = new LinkedHashMap<>();
        try {
            out.put(DepthVariantBook.BOOK, depthVariant.book().positions());
        } catch (RuntimeException e) {
            log.warn("FastWatch 读取 V2D15 持仓失败：{}", e.toString());
        }
        try {
            for (var v : variants.registry().managed()) {
                if (!com.trendpilot.variant.VariantRegistry.fastWatchCovers(v.overrides())) continue;
                out.put(v.name(), v.book().positions());
            }
        } catch (RuntimeException e) {
            log.warn("FastWatch 读取影子变体持仓失败：{}", e.toString());
        }
        return out;
    }
}
