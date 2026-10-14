package com.trendpilot.research;

/** 身份核验结论。 */
public enum Identity {
    /** CoinGecko 上该交易所该交易对唯一对应一个项目。 */
    VERIFIED,
    /** 同一交易对对应多个项目：明确的身份问题。 */
    AMBIGUOUS,
    /** CoinGecko 上找不到该交易对。 */
    UNVERIFIED,
    /** 身份资料还没加载成功（例如 CoinGecko 不可用）。 */
    DATA_UNAVAILABLE
}
