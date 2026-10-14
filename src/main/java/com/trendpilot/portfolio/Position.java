package com.trendpilot.portfolio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;

import com.trendpilot.market.Venue;

/**
 * V2 影子持仓。venue 是模拟成交所在的交易所（Bybit 独立池的标的在 Bybit）。
 *costBasis 含买入手续费；entryNotional 不含手续费，用于计算原始收益。
 * 利润保护状态（armed / floor / peakNet）在加仓后重置。
 */
public record Position(
        Venue venue,
        String symbol,
        BigDecimal qty,
        BigDecimal costBasis,
        BigDecimal entryNotional,
        int tranches,
        BigDecimal lastTranchePrice,
        Instant lastSignalAt,
        String lastGeneration,
        Instant openedAt,
        boolean protectArmed,
        BigDecimal protectFloor,
        BigDecimal peakNet,
        int weakStreak,
        boolean lossRecovery) {

    public BigDecimal avgEntryPrice() {
        return entryNotional.divide(qty, MathContext.DECIMAL64);
    }

    public Position withProtection(boolean armed, BigDecimal floor, BigDecimal peak) {
        return new Position(venue, symbol, qty, costBasis, entryNotional, tranches, lastTranchePrice, lastSignalAt,
                lastGeneration, openedAt, armed, floor, peak, weakStreak, lossRecovery);
    }

    public Position withHealth(int streak, boolean recovery) {
        return new Position(venue, symbol, qty, costBasis, entryNotional, tranches, lastTranchePrice, lastSignalAt,
                lastGeneration, openedAt, protectArmed, protectFloor, peakNet, streak, recovery);
    }
}
