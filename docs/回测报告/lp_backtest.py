"""Uniswap V3 集中流动性 LP 回测
数据: CoinGecko ETH 小时价格(1年) + DefiLlama 池子每日手续费收益(apyBase)与TVL
用法: python3 -I lp_backtest.py <data_dir>
"""
import json, math, sys, glob, datetime as dt
from collections import defaultdict

D = sys.argv[1]
prices = json.load(open(f"{D}/eth_1h.json"))  # [[hour_index, price], ...]

POOLS = {
    "ETH主网 0.05%": ("665dc8bc", 3.0),   # (chart id 前缀, 每次调仓gas美元)
    "Arbitrum 0.05%": ("a14bd201", 0.2),
    "Base 0.05%": ("8b3cadb9", 0.2),
    "Base 0.3%": ("b99bcdf5", 0.2),
}
SWAP_COST = 0.0008      # 调仓换币: 0.05%池费 + 0.03%滑点
HEDGE_COST = 0.0004     # 合约对冲调整成本(taker 0.04%)


def load_pool(prefix):
    f = glob.glob(f"{D}/chart_{prefix}*.json")[0]
    daily = {}
    for x in json.load(open(f))["data"]:
        if x.get("apyBase") is None:
            continue
        day = x["timestamp"][:10]
        daily[day] = x["apyBase"] / 100 / 365 * x["tvlUsd"], x["tvlUsd"]  # (当日手续费总额, TVL)
    return daily


def amounts(L, P, a, b):
    sp, sa, sb = math.sqrt(P), math.sqrt(a), math.sqrt(b)
    if P <= a:
        return L * (1 / sa - 1 / sb), 0.0
    if P >= b:
        return 0.0, L * (sb - sa)
    return L * (1 / sp - 1 / sb), L * (sp - sa)


def value(L, P, a, b):
    x, y = amounts(L, P, a, b)
    return x * P + y


def liq_for(V, P, a, b):
    return V / value(1.0, P, a, b)


def run(pool, width, V0=10000, m_avg=10, rebalance=True, hedge=False, gas=0.2):
    """width: 区间半宽(0.1=±10%); None=全区间"""
    daily = load_pool(POOLS[pool][0])
    series = [(h, p) for h, p in prices
              if dt.datetime.utcfromtimestamp(h * 3600).strftime("%Y-%m-%d") in daily]
    h0, P0 = series[0]

    def rng(P):
        if width is None:
            return P / 1e6, P * 1e6
        return P * (1 - width), P * (1 + width)

    a, b = rng(P0)
    L = liq_for(V0, P0, a, b)
    fees = costs = hedge_pnl = 0.0
    n_reb = 0
    in_range_h = 0
    short = amounts(L, P0, a, b)[0] if hedge else 0.0
    prevP = P0
    for h, P in series[1:]:
        day = dt.datetime.utcfromtimestamp(h * 3600).strftime("%Y-%m-%d")
        day_fees, tvl = daily[day]
        if hedge:
            hedge_pnl += -short * (P - prevP)
        if a < P < b:
            in_range_h += 1
            L_pool = m_avg * tvl / (2 * math.sqrt(P))
            fees += day_fees / 24 * L / (L_pool + L)
        elif rebalance:
            Vnow = value(L, P, a, b)
            c = gas + 0.5 * Vnow * SWAP_COST
            costs += c
            n_reb += 1
            a, b = rng(P)
            L = liq_for(Vnow - c, P, a, b)
        if hedge:
            target = amounts(L, P, a, b)[0]
            if abs(target - short) * P > 0.02 * value(L, P, a, b):  # 偏离2%才调整
                costs += abs(target - short) * P * HEDGE_COST
                short = target
        prevP = P
    Pend = series[-1][1]
    lp_val = value(L, Pend, a, b)
    total = lp_val + fees + hedge_pnl
    days = len(series) / 24
    hold5050 = V0 / 2 + V0 / 2 * Pend / P0
    return dict(pool=pool, width=width, days=round(days), fees=fees, costs=costs,
                n_reb=n_reb, in_range=in_range_h / len(series), lp_val=lp_val,
                hedge_pnl=hedge_pnl, total=total, ret=total / V0 - 1,
                fee_apr=fees / V0 * 365 / days, hold5050=hold5050 / V0 - 1,
                eth_ret=Pend / P0 - 1, P0=P0, Pend=Pend)


if __name__ == "__main__":
    out = []
    widths = [0.02, 0.05, 0.10, 0.20, 0.50, None]
    for pool, (_, gas) in POOLS.items():
        for w in widths:
            for hedge in (False, True):
                for m in (5, 10, 20):
                    for V0 in (1000, 10000, 100000):
                        r = run(pool, w, V0=V0, m_avg=m, hedge=hedge, gas=gas)
                        r.update(m_avg=m, hedge=hedge, V0=V0, rebalance=True)
                        out.append(r)
            r = run(pool, w, V0=10000, m_avg=10, rebalance=False, gas=gas)
            r.update(m_avg=10, hedge=False, V0=10000, rebalance=False)
            out.append(r)
    json.dump(out, open(f"{D}/results.json", "w"), ensure_ascii=False, indent=1)
    print("runs:", len(out))
