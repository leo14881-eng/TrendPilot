package com.trendpilot.lpshadow;

/**
 * 一个策略（或基准）的最新状态，内存里只留这一份，每分钟整体写回 lp_state（JSON）。金额单位都是 USDC，数量是人类单位。
 * 价值 = 做市仓位价值 + 已收手续费（不复投）+ 对冲账户权益（保证金现金 + 空单浮盈亏）。
 */
public class PositionState {

    public String strategy;
    public String pool;
    public boolean started;
    public long startTs;
    public double startPrice;

    // ----- 做市仓位 -----
    public long positionId;
    public int tickLower;
    public int tickUpper;
    public double liquidity;
    /** 这一段（开仓 / 上次调仓以来）开仓时的 WETH / USDC 数量，用于计算无常损失。 */
    public double segWeth;
    public double segUsdc;
    /** 首次出区间的采样时间（epoch 秒），在区间内为 0。 */
    public long outSince;

    // ----- 累计 -----
    public double fees;
    public double ilRealized;
    public double gasCost;         // 链上成本合计：L2 执行费 + L1 数据费
    public double l1Cost;          // 其中 L1 数据费
    public double swapCost;
    public int rebalances;

    // ----- 基准 -----
    public double holdWeth;
    public double holdUsdc;

    // ----- 对冲（空 ETHUSDT 永续）-----
    public double initMargin;      // 开始时划给保证金的资金（3 倍杠杆，不参与做市）
    public double margin;          // 保证金账户现金（含已实现盈亏、资金费、已扣 taker 手续费）
    public double hedgeQty;        // 空单数量（ETH）
    public double hedgeEntry;      // 平均开仓价
    public double hedgeRealized;   // 已实现价格盈亏
    public double hedgeFees;       // taker 手续费累计（也计入 swapCost）
    public double funding;         // 资金费累计（正 = 收到）
    public int hedgeTrades;
    public long lastFundingBoundary; // 最近一次处理过的资金费结算时间（epoch 秒，每 8 小时）
    public int riskEvents;
    public boolean atRisk;
    public boolean liquidated;

    // ----- 最近估值 -----
    public long lastTs;
    public double lastPrice;
    public double lastLpValue;
    public double lastValue;
    public double lastIl;
    public double lastHedgePnl;
    public int validMinutes;
}
