#!/usr/bin/env python3
"""LP 影子盘回放测试的参考实现（独立于 Java 代码，按 docs/规则说明.md "LP 影子盘" 的规则重新写一遍）。

生成：
  replay_snapshots.csv  —— 两个池子 11 个小时的分钟快照（WETH 是 token0 / USDC 是 token0 各一个），
                           含 GAP 行、缺分钟、永续报价缺失、gas 缺失、feeGrowth 跨 2^256 回绕、两次资金费结算、
                           先涨 12% 再回落的行情（窄区间会多次调仓）。
  replay_expected.csv   —— 每个策略最后的价值、手续费、无常损失、各项成本、对冲盈亏、资金费、调仓次数等。
Java 的 LpReplayTest 读快照、跑 LpBook，结果必须和这里一致。重新生成：python3 replay_reference.py
"""
import csv
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
Q96 = 2.0 ** 96
Q128 = 2.0 ** 128
TWO256 = 2 ** 256
LN = math.log(1.0001)
MIN_TICK, MAX_TICK = -887272, 887272
CAPITAL = 10_000.0
WIDTHS = [0.02, 0.05, 0.10, 0.20, 0.50]
COOLDOWNS = [0, 30]
GAS = 600_000
COST_MAX_AGE = 3600
SLIP = 0.0002
BAND = 0.02
TAKER = 0.0005
LEV = 3.0
MMR = 0.005
RISK = 0.15
FPER = 8 * 3600

POOLS = {
    # key: (dec0, dec1, weth_is_token0, fee, spacing)
    "rw0": (18, 6, True, 500, 10),
    "ru0": (6, 18, False, 3000, 60),
}


# ---------------- 价格换算 ----------------
def eth_price(p, raw):
    dec0, dec1, w0 = p[0], p[1], p[2]
    human = raw * 10.0 ** (dec0 - dec1)
    return human if w0 else 1.0 / human


def raw_price(p, eth):
    dec0, dec1, w0 = p[0], p[1], p[2]
    human = eth if w0 else 1.0 / eth
    return human / 10.0 ** (dec0 - dec1)


def sqrt_at_tick(t):
    return math.exp(t * LN / 2)


def tick_at_raw(raw):
    t = int(math.floor(math.log(raw) / LN))
    if 1.0001 ** (t + 1) <= raw:
        t += 1
    if 1.0001 ** t > raw:
        t -= 1
    return t


def amounts(L, sa, sb, s):
    if s <= sa:
        return L * (sb - sa) / (sa * sb), 0.0
    if s >= sb:
        return 0.0, L * (sb - sa)
    return L * (sb - s) / (s * sb), L * (s - sa)


def weth_usdc(p, L, lo, hi, s):
    a0, a1 = amounts(L, sqrt_at_tick(lo), sqrt_at_tick(hi), s)
    a0 /= 10.0 ** p[0]
    a1 /= 10.0 ** p[1]
    return (a0, a1) if p[2] else (a1, a0)


def value(p, L, lo, hi, s):
    w, u = weth_usdc(p, L, lo, hi, s)
    return w * eth_price(p, s * s) + u


def liquidity_for(p, v, lo, hi, s):
    unit = value(p, 1.0, lo, hi, s)
    return v / unit if unit > 0 else 0.0


def floor_div(a, b):
    return a // b


def range_ticks(p, s, w):
    eth = eth_price(p, s * s)
    ra, rb = raw_price(p, eth * (1 - w)), raw_price(p, eth * (1 + w))
    sp = p[4]
    lo = floor_div(tick_at_raw(min(ra, rb)), sp) * sp
    hi = -floor_div(-tick_at_raw(max(ra, rb)), sp) * sp
    min_t = -floor_div(-MIN_TICK, sp) * sp
    max_t = floor_div(MAX_TICK, sp) * sp
    lo, hi = max(lo, min_t), min(hi, max_t)
    if hi <= lo:
        hi = lo + sp
    return lo, hi


def in_range(t, lo, hi):
    return lo <= t < hi


# ---------------- 数据生成 ----------------
def lcg(seed):
    x = seed
    while True:
        x = (x * 6364136223846793005 + 1442695040888963407) % (2 ** 64)
        yield (x >> 11) / float(2 ** 53)


def generate():
    start = 1_793_512_800  # 2026-11-01T06:00:00Z
    rows = []
    for key, p in POOLS.items():
        rnd = lcg(42 if key == "rw0" else 7)
        eth = 3000.0
        fg0 = 0 if key == "ru0" else 123456789
        fg1 = TWO256 - int(Q128 * 2e-12) if key == "rw0" else 987654321  # rw0 的 fg1 在第 10 分钟左右回绕
        L_active = 1.5e18 if key == "rw0" else 2.0e15
        for i in range(660):
            ts = start + 60 * i
            # 先涨约 12%（第 120~240 分钟）再回落（第 360~480 分钟），叠加小波动
            drift = 0.001 if 120 <= i < 240 else (-0.001 if 360 <= i < 480 else 0.0)
            eth *= math.exp(drift + (next(rnd) - 0.5) * 0.002)
            raw = raw_price(p, eth)
            sqrt_x96 = int(math.sqrt(raw) * Q96)
            tick = tick_at_raw((sqrt_x96 / Q96) ** 2)
            liq = int(L_active * (0.8 + 0.4 * next(rnd)))
            vol = 0.5 + next(rnd)
            if p[2]:
                fg0 = (fg0 + int(Q128 * 4e-7 * vol)) % TWO256
                fg1 = (fg1 + int(Q128 * 1.2e-12 * vol)) % TWO256
            else:
                fg0 = (fg0 + int(Q128 * 1e-11 * vol)) % TWO256
                fg1 = (fg1 + int(Q128 * 4e-9 * vol)) % TWO256
            gas = 10_000_000 if p[2] else 15_000_000
            mark = eth * 1.0002
            hour = (ts // 3600) % 24
            rate = 0.0001 if hour < 8 else (0.00015 if hour < 14 else -0.00005)
            status, note = "OK", ""
            if key == "rw0" and i == 100:
                status = "GAP"
            if key == "ru0" and i == 50:
                status = "GAP"
            if key == "rw0" and 200 <= i <= 202:
                continue  # 整段缺分钟
            if key == "rw0" and i == 300:
                mark, rate = None, None  # 永续报价缺失
            if key == "rw0" and i == 400:
                gas = None
            l1 = 1_500_000_000_000 if p[2] else 9_000_000_000_000  # 一次调仓的 L1 数据费（wei）
            if key == "rw0" and 400 <= i < 540:
                l1 = None  # L1 费缺 140 分钟：前 60 分钟沿用最近值，之后不调仓
            if status == "GAP":
                rows.append([ts, key, "", "", "", "", "", "", mark if mark else "", rate if rate is not None else "", "GAP", ""])
            else:
                rows.append([ts, key, str(sqrt_x96), tick, str(liq), str(fg0), str(fg1), "" if gas is None else str(gas),
                             "" if mark is None else repr(mark), "" if rate is None else repr(rate), "OK",
                             "" if l1 is None else str(l1)])
    return rows


# ---------------- 参考引擎 ----------------
class St:
    def __init__(self):
        self.__dict__.update(dict(started=False, lo=0, hi=0, L=0.0, seg_w=0.0, seg_u=0.0, out_since=0, fees=0.0,
                                  il_real=0.0, gas=0.0, swap=0.0, reb=0, hold_w=0.0, hold_u=0.0, init_margin=0.0,
                                  margin=0.0, q=0.0, entry=0.0, h_real=0.0, h_fees=0.0, funding=0.0, h_trades=0,
                                  last_fb=0, risk=0, at_risk=False, liq=False, value=0.0, il=0.0, hedge_pnl=0.0,
                                  valid=0))


def strategies(pool):
    out = []
    for w in WIDTHS:
        for h in (False, True):
            for c in COOLDOWNS:
                out.append(("%s-w%02d-%s-c%d" % (pool, round(w * 100), "H" if h else "U", c), "LP", w, h, c))
    out += [(pool + "-hold5050", "H5050", 0, False, 0), (pool + "-usdc", "HUSDC", 0, False, 0),
            (pool + "-eth", "HETH", 0, False, 0)]
    return out


def parse(r):
    ok = r[10] == "OK"
    return dict(ts=int(r[0]), ok=ok,
                sqrt=int(r[2]) / Q96 if ok else None, tick=int(r[3]) if ok else None,
                liq=int(r[4]) if ok else None, fg0=int(r[5]) if ok else None, fg1=int(r[6]) if ok else None,
                gas=int(r[7]) if ok and r[7] != "" else None,
                mark=float(r[8]) if r[8] != "" else None, rate=float(r[9]) if r[9] != "" else None,
                l1=int(r[11]) if ok and r[11] != "" else None)


def perp_ok(s):
    return s["mark"] is not None and s["mark"] > 0 and s["rate"] is not None


def fee_usdc(p, a, b, lo, hi, L):
    ia, ib = in_range(a["tick"], lo, hi), in_range(b["tick"], lo, hi)
    f = 1.0 if ia and ib else (0.5 if ia or ib else 0.0)
    if f == 0 or L <= 0:
        return 0.0
    act = float(a["liq"])
    share = act / (act + L)
    d0 = (b["fg0"] - a["fg0"]) % TWO256
    d1 = (b["fg1"] - a["fg1"]) % TWO256
    fee0 = L * (float(d0) / Q128) * share * f / 10.0 ** p[0]
    fee1 = L * (float(d1) / Q128) * share * f / 10.0 ** p[1]
    price = eth_price(p, b["sqrt"] * b["sqrt"])
    return fee0 * price + fee1 if p[2] else fee0 + fee1 * price


def run_pool(key, snaps):
    p = POOLS[key]
    strs = strategies(key)
    st = {s[0]: St() for s in strs}
    prev = None
    last_rate = last_mark = None
    last_rate_ts = 0
    cost = dict(gas=None, gas_ts=0, l1=None, l1_ts=0)

    def started():
        return any(x.started for x in st.values())

    def open_range(s, x, sqrt, cap):
        x.lo, x.hi = range_ticks(p, sqrt, s[2])
        x.L = liquidity_for(p, cap, x.lo, x.hi, sqrt)
        x.seg_w, x.seg_u = weth_usdc(p, x.L, x.lo, x.hi, sqrt)
        x.out_since = 0

    def adjust(s, x, cur, sqrt):
        if x.liq or not perp_ok(cur):
            return
        mark = cur["mark"]
        lp = value(p, x.L, x.lo, x.hi, sqrt)
        target = weth_usdc(p, x.L, x.lo, x.hi, sqrt)[0]
        diff = target - x.q
        if abs(diff) * mark <= BAND * lp:
            return
        fee = abs(diff) * mark * TAKER
        if diff > 0:
            x.entry = (x.q * x.entry + diff * mark) / (x.q + diff)
        else:
            realized = -diff * (x.entry - mark)
            x.h_real += realized
            x.margin += realized
        x.q = target
        if x.q <= 0:
            x.q, x.entry = 0.0, 0.0
        x.margin -= fee
        x.h_fees += fee
        x.swap += fee
        x.h_trades += 1

    def liq_price(m, q, e):
        return (m + q * e) / (q * (1 + MMR)) if q > 0 else math.inf

    def check_liq(x, cur):
        if x.liq or x.q <= 0 or not perp_ok(cur):
            x.at_risk = False
            return
        mark = cur["mark"]
        lq = liq_price(x.margin, x.q, x.entry)
        if mark >= lq:
            x.h_real += x.q * (x.entry - mark)
            x.margin = x.q = x.entry = 0.0
            x.liq = True
            x.at_risk = True
            x.risk += 1
            return
        if lq / mark - 1 < RISK:
            if not x.at_risk:
                x.risk += 1
            x.at_risk = True
        else:
            x.at_risk = False

    def mark_value(s, x, cur, sqrt):
        price = eth_price(p, sqrt * sqrt)
        if s[1] != "LP":
            x.value = x.hold_w * price + x.hold_u
            return
        lp = value(p, x.L, x.lo, x.hi, sqrt)
        x.il = x.il_real + (lp - (x.seg_w * price + x.seg_u))
        hm = cur["mark"] if perp_ok(cur) else price
        eq = x.margin + x.q * (x.entry - hm)
        x.hedge_pnl = eq - x.init_margin - x.funding + x.h_fees if s[3] else 0.0
        x.value = lp + x.fees + (eq if s[3] else 0.0)

    for cur in snaps:
        if prev is not None and cur["ts"] <= prev["ts"]:
            continue
        # 资金费
        if started():
            boundary = cur["ts"] - cur["ts"] % FPER
            mark = cur["mark"] if perp_ok(cur) else last_mark
            known = last_rate is not None and last_rate_ts < boundary and last_rate_ts >= boundary - FPER and mark is not None
            for s in strs:
                if not s[3]:
                    continue
                x = st[s[0]]
                if not x.started or boundary <= x.last_fb:
                    continue
                x.last_fb = boundary
                if x.q <= 0 or not known:
                    continue
                amt = x.q * mark * last_rate
                x.margin += amt
                x.funding += amt
        if perp_ok(cur):
            last_rate, last_rate_ts, last_mark = cur["rate"], cur["ts"], cur["mark"]
        if cur["ok"]:
            if cur["gas"] is not None:
                cost["gas"], cost["gas_ts"] = cur["gas"], cur["ts"]
            if cur["l1"] is not None:
                cost["l1"], cost["l1_ts"] = cur["l1"], cur["ts"]
        if not cur["ok"]:
            prev = cur
            continue
        pool_valid = prev is not None and prev["ok"] and cur["ts"] - prev["ts"] == 60
        sqrt = cur["sqrt"]
        if not started():
            if perp_ok(cur):
                price = eth_price(p, sqrt * sqrt)
                for s in strs:
                    x = st[s[0]]
                    x.started = True
                    x.last_fb = cur["ts"] - cur["ts"] % FPER
                    if s[1] == "H5050":
                        x.hold_u, x.hold_w = CAPITAL / 2, CAPITAL / 2 / price
                    elif s[1] == "HUSDC":
                        x.hold_u = CAPITAL
                    elif s[1] == "HETH":
                        x.hold_w = CAPITAL / price
                    else:
                        cap = CAPITAL
                        if s[3]:
                            lo, hi = range_ticks(p, sqrt, s[2])
                            uw, uu = weth_usdc(p, 1.0, lo, hi, sqrt)
                            f = uw * price / (uw * price + uu)
                            cap = CAPITAL / (1 + f / LEV)
                            x.init_margin = CAPITAL - cap
                            x.margin = x.init_margin
                        open_range(s, x, sqrt, cap)
                    if s[3]:
                        adjust(s, x, cur, sqrt)
                    mark_value(s, x, cur, sqrt)
        else:
            for s in strs:
                x = st[s[0]]
                if not x.started:
                    continue
                valid = pool_valid and (not s[3] or perp_ok(cur))
                if valid:
                    x.valid += 1
                if s[1] == "LP" and valid:
                    x.fees += fee_usdc(p, prev, cur, x.lo, x.hi, x.L)
                    # 调仓
                    if in_range(cur["tick"], x.lo, x.hi):
                        x.out_since = 0
                    else:
                        if x.out_since == 0:
                            x.out_since = cur["ts"]
                        have = (cost["gas"] is not None and cur["ts"] - cost["gas_ts"] <= COST_MAX_AGE
                                and cost["l1"] is not None and cur["ts"] - cost["l1_ts"] <= COST_MAX_AGE)
                        if cur["ts"] - x.out_since >= s[4] * 60 and have:
                            price = eth_price(p, sqrt * sqrt)
                            lp = value(p, x.L, x.lo, x.hi, sqrt)
                            gas = (float(cost["gas"]) * GAS + float(cost["l1"])) / 1e18 * price
                            swap = lp / 2 * (p[3] / 1_000_000.0 + SLIP)
                            cap = lp - gas - swap
                            if cap > 0:
                                x.il_real += lp - (x.seg_w * price + x.seg_u)
                                open_range(s, x, sqrt, cap)
                                x.gas += gas
                                x.swap += swap
                                x.reb += 1
                    if s[3]:
                        check_liq(x, cur)
                        adjust(s, x, cur, sqrt)
                mark_value(s, x, cur, sqrt)
        prev = cur
    return [(s[0], st[s[0]]) for s in strs]


def main():
    rows = generate()
    with open(os.path.join(HERE, "replay_snapshots.csv"), "w", newline="\n") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["ts", "pool", "sqrt_price_x96", "tick", "liquidity", "fg0", "fg1", "gas_price_wei", "perp_mark",
                    "funding_rate", "status", "l1_fee_wei"])
        w.writerows(rows)
    with open(os.path.join(HERE, "replay_snapshots.csv")) as f:
        parsed = [r for r in csv.reader(f)][1:]
    out = []
    for key in POOLS:
        snaps = [parse(r) for r in parsed if r[1] == key]
        for name, x in run_pool(key, snaps):
            out.append([name, repr(x.value), repr(x.fees), repr(x.il), repr(x.gas), repr(x.swap), repr(x.hedge_pnl),
                        repr(x.funding), x.reb, x.h_trades, x.risk, x.valid])
    with open(os.path.join(HERE, "replay_expected.csv"), "w", newline="\n") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["strategy", "value", "fees", "il", "gas_cost", "swap_cost", "hedge_pnl", "funding", "rebalances",
                    "hedge_trades", "risk_events", "valid_minutes"])
        w.writerows(out)
    for r in out:
        print(r[0], round(float(r[1]), 2), "fees", round(float(r[2]), 2), "reb", r[8], "hedge", r[9], "fund", round(float(r[7]), 3))


if __name__ == "__main__":
    main()
