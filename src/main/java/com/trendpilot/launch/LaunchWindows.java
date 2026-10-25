package com.trendpilot.launch;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个币最近 1 小时的价格和成交额滚动窗口（10 秒一格，共 360 格，原始类型数组的环形缓冲；不保存逐条行情）。
 * 输入是 15m K 线的实时推送：当前价 + 本根 K 线累计成交额（换根时从 0 重新累计），相邻两次的差就是这段时间的成交额。
 * <ul>
 * <li>推送中断超过 {@link #MAX_GAP_SEC} 秒（断线、交易所没推）→ 窗口作废，重新攒满 1 小时才判断：数据缺失只会不记录。</li>
 * <li>启动 = 5 分钟涨幅 ≥ 最低一档 且 最近 5 分钟成交额 ≥ 之前 55 分钟每 5 分钟平均 × 最低倍数；同一个币 1 小时内只记一次。</li>
 * </ul>
 */
public final class LaunchWindows {

    static final int SLOT_SEC = 10;
    static final int SLOTS = 360;
    static final int MAX_GAP_SEC = 60;
    static final int COOLDOWN_SLOTS = SLOTS;

    private final double minRet5Pct;
    private final double minVolRatio;
    private final Map<String, Sym> map = new ConcurrentHashMap<>();

    public LaunchWindows(double minRet5Pct, double minVolRatio) {
        this.minRet5Pct = minRet5Pct;
        this.minVolRatio = minVolRatio;
    }

    /** 一次启动：涨幅（%）和成交额放大倍数。 */
    public record Launch(double price, double ret1mPct, double ret5mPct, double ret15mPct, double volRatio) {
    }

    private static final class Sym {
        final float[] price = new float[SLOTS];
        final float[] vol = new float[SLOTS];
        long slot = -1;
        long lastSec = -1;
        long barOpen = -1;
        double lastCum;
        int filled;
        long cooldownUntil = -1;
    }

    public int size() {
        return map.size();
    }

    /** 释放超过 2 小时没有推送的币（已不在订阅范围）。 */
    public void prune(long epochMs) {
        long sec = epochMs / 1000;
        map.values().removeIf(s -> {
            synchronized (s) {
                return s.lastSec >= 0 && sec - s.lastSec > 7200;
            }
        });
    }

    /**
     * @param cumQuote 本根 15m K 线到现在的累计成交额（U）
     * @param barOpen  这根 K 线的开盘时间
     * @return 达到启动条件时返回启动信息，否则 null
     */
    public Launch onTick(String key, double price, double cumQuote, long barOpen, long epochMs) {
        if (!(price > 0) || !(cumQuote >= 0)) return null;
        Sym s = map.computeIfAbsent(key, k -> new Sym());
        synchronized (s) {
            long sec = epochMs / 1000;
            long slot = sec / SLOT_SEC;
            if (s.lastSec >= 0 && (sec - s.lastSec > MAX_GAP_SEC || slot < s.slot)) {
                s.filled = 0; // 中断太久或时间倒退：重新攒
                s.slot = -1;
            }
            double delta;
            if (s.barOpen != barOpen) delta = s.barOpen < 0 ? 0 : cumQuote; // 换根：新一根从 0 累计
            else delta = Math.max(0, cumQuote - s.lastCum);
            s.barOpen = barOpen;
            s.lastCum = cumQuote;
            s.lastSec = sec;
            boolean advanced = false;
            if (s.slot < 0) {
                s.slot = slot;
                int i = idx(slot);
                s.price[i] = (float) price;
                s.vol[i] = 0;
                s.filled = 1;
            } else {
                for (long t = s.slot + 1; t <= slot; t++) {
                    int i = idx(t);
                    s.price[i] = s.price[idx(t - 1)];
                    s.vol[i] = 0;
                    if (s.filled < SLOTS) s.filled++;
                    advanced = true;
                }
                s.slot = slot;
            }
            int i = idx(slot);
            s.price[i] = (float) price;
            s.vol[i] += (float) delta;
            if (!advanced || s.filled < SLOTS || slot < s.cooldownUntil) return null;
            double p1 = s.price[idx(slot - 6)], p5 = s.price[idx(slot - 30)], p15 = s.price[idx(slot - 90)];
            double ret5 = (price / p5 - 1) * 100;
            if (!(ret5 >= minRet5Pct)) return null;
            double recent = 0, rest = 0;
            for (int k = 0; k < SLOTS; k++) {
                double v = s.vol[idx(slot - k)];
                if (k < 30) recent += v; else rest += v;
            }
            double base = rest / ((SLOTS - 30) / 30.0);
            double ratio = base > 0 ? recent / base : Double.POSITIVE_INFINITY;
            if (!(ratio >= minVolRatio) || base <= 0) return null;
            s.cooldownUntil = slot + COOLDOWN_SLOTS;
            return new Launch(price, (price / p1 - 1) * 100, ret5, (price / p15 - 1) * 100, ratio);
        }
    }

    private static int idx(long slot) {
        return (int) Math.floorMod(slot, (long) SLOTS);
    }
}
