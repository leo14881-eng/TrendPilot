package com.trendpilot.market;

public class MarketDataException extends RuntimeException {

    public MarketDataException(String message, Throwable cause) {
        super(message, cause);
    }

    public MarketDataException(String message) {
        super(message);
    }
}
