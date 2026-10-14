package com.trendpilot.common;

/**
 * 资金权限。本系统只做影子模拟，唯一取值就是 NONE_SHADOW_ONLY，
 * 所有 BUY/ADD/SELL 都只写入影子账本，不会产生真实订单。
 */
public enum CapitalAuthority {
    NONE_SHADOW_ONLY;

    public static final CapitalAuthority CURRENT = NONE_SHADOW_ONLY;
}
