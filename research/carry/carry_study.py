#!/usr/bin/env python3
"""Funding-rate carry study (research only, never used live).

Delta-neutral carry: long spot + short USDT-M perpetual of the same coin with equal
notional, earning the funding rate while price moves cancel out. Data comes only from
data.binance.vision public monthly archives (no API calls).

All groups and thresholds are fixed in GROUPS below before any result was seen
(pre-registered 2026-11-12). Design period 2022-10-01..2024-12-31, validation
2025-01-01..2026-10-31.

Usage: python3 carry_study.py <cache_dir> <out_dir> [--selftest]
"""
import concurrent.futures as cf
import csv
import io
import json
import math
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
import zipfile
from datetime import datetime, timezone

import numpy as np

BASE = "https://data.binance.vision/"
LIST = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision"
DATA_START = "2022-08"
SIM_START = datetime(2022, 10, 1, tzinfo=timezone.utc)
SIM_END = datetime(2026, 11, 1, tzinfo=timezone.utc)   # exclusive
SPLIT = datetime(2025, 1, 1, tzinfo=timezone.utc)
H0 = datetime(2022, 8, 1, tzinfo=timezone.utc)
STABLES = {"USDC", "FDUSD", "TUSD", "BUSD", "USDP", "DAI", "USDE", "EUR", "AEUR", "UST", "USTC", "PAX", "USD1", "XUSD", "BFUSD"}
UNIVERSE_N = 80          # may enter: top 80 by previous-month perp quote volume
UNIVERSE_HOLD = 100      # may keep holding while in top 100
MIN_AGE_H = 30 * 24      # perp listed at least 30 days

# Pre-registered groups. t_in / t_out are annualised funding over the last 72h.
GROUPS = [
    dict(name="C1_BTCETH_always", mode="fixed", syms=["BTCUSDT", "ETHUSDT"], n=2, t_in=-9.0, t_out=-9.0),
    dict(name="C2_BTCETH_gate10", mode="fixed", syms=["BTCUSDT", "ETHUSDT"], n=2, t_in=0.10, t_out=0.0),
    dict(name="C3_top5_in15", mode="universe", n=5, t_in=0.15, t_out=0.0),
    dict(name="C4_top10_in15", mode="universe", n=10, t_in=0.15, t_out=0.0),
    dict(name="C5_top10_in25", mode="universe", n=10, t_in=0.25, t_out=0.0),
]
COSTS = dict(spot_fee=0.0010, perp_fee=0.0005, slip=0.0005)   # per leg, per side
MARGIN = 0.5            # perp margin reserved = 0.5 x notional -> capital per position = 1.5 x notional
RESET_UP = 0.30         # perp close >= entry * 1.30 -> close and reopen both legs (move margin)
LIQ_UP = 0.45           # perp hourly high >= entry * 1.45 before reset -> liquidated
LIQ_PENALTY = 0.01
DELIST_GAP_H = 48
DELIST_PENALTY = 0.02
TWINS = 300


def months(start, end_excl):
    y, m = map(int, start.split("-"))
    out = []
    while True:
        s = f"{y:04d}-{m:02d}"
        if s >= end_excl:
            return out
        out.append(s)
        m += 1
        if m == 13:
            y, m = y + 1, 1


MONTHS = months(DATA_START, "2026-11")


def http_get(url, tries=5):
    for i in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            time.sleep(2 * (i + 1))
        except Exception:
            time.sleep(2 * (i + 1))
    raise RuntimeError("download failed " + url)


def list_prefixes(prefix):
    out, marker = [], ""
    while True:
        url = f"{LIST}?delimiter=/&prefix={prefix}" + (f"&marker={marker}" if marker else "")
        xml = http_get(url).decode()
        found = re.findall(r"<Prefix>" + re.escape(prefix) + r"([^<]+)/</Prefix>", xml)
        out += found
        if "<IsTruncated>true</IsTruncated>" not in xml or not found:
            return sorted(set(out))
        marker = prefix + found[-1] + "/"


def cached(cache, path):
    """Return zip bytes or None (404). Cached on disk."""
    f = os.path.join(cache, path)
    if os.path.exists(f):
        return open(f, "rb").read() or None
    os.makedirs(os.path.dirname(f), exist_ok=True)
    b = http_get(BASE + path)
    with open(f + ".tmp", "wb") as w:
        w.write(b or b"")
    os.replace(f + ".tmp", f)
    return b


def rows(b):
    if not b:
        return []
    with zipfile.ZipFile(io.BytesIO(b)) as z:
        txt = z.read(z.namelist()[0]).decode()
    out = []
    for r in csv.reader(io.StringIO(txt)):
        if r and r[0][:1].isdigit():
            out.append(r)
    return out


def ms(v):
    v = int(float(v))
    while v > 10 ** 13:      # spot files from 2025 use microseconds
        v //= 1000
    return v


def hour_index(t_ms):
    return int((t_ms // 1000 - int(H0.timestamp())) // 3600)


NH = hour_index(int(SIM_END.timestamp() * 1000))


def spot_name(perp):
    base = perp[:-4]
    for p in ("1000000", "1000", "1M"):
        if base.startswith(p) and len(base) > len(p):
            return base[len(p):] + "USDT"
    return perp


def load(cache, log):
    perps = [s for s in list_prefixes("data/futures/um/monthly/fundingRate/")
             if s.endswith("USDT") and "_" not in s and s[:-4] not in STABLES]
    spots = set(list_prefixes("data/spot/monthly/klines/"))
    perps = [p for p in perps if spot_name(p) in spots]
    log(f"perps with spot pair: {len(perps)}")

    # Phase A: monthly perp quote volume from 1d klines
    jobs = [(p, m) for p in perps for m in MONTHS]
    vol = {}

    def fa(j):
        p, m = j
        b = cached(cache, f"data/futures/um/monthly/klines/{p}/1d/{p}-1d-{m}.zip")
        return j, sum(float(r[7]) for r in rows(b))
    with cf.ThreadPoolExecutor(48) as ex:
        for (p, m), v in ex.map(fa, jobs):
            if v > 0:
                vol[(p, m)] = v
    rank = {}   # month -> {sym: rank by previous month volume}
    for i, m in enumerate(MONTHS[1:], 1):
        prev = MONTHS[i - 1]
        lst = sorted([p for p in perps if (p, prev) in vol], key=lambda p: -vol[(p, prev)])
        rank[m] = {p: k for k, p in enumerate(lst)}
    need = sorted({p for m in rank for p, k in rank[m].items() if k < UNIVERSE_HOLD} | {"BTCUSDT", "ETHUSDT"})
    log(f"symbols ever in top {UNIVERSE_HOLD}: {len(need)}")

    # Phase B: hourly klines (spot + perp) and funding
    data = {}

    def fb(p):
        sp = spot_name(p)
        s_close = np.full(NH, np.nan); p_close = np.full(NH, np.nan); p_high = np.full(NH, np.nan)
        fund = np.zeros(NH)
        for m in MONTHS:
            for r in rows(cached(cache, f"data/futures/um/monthly/klines/{p}/1h/{p}-1h-{m}.zip")):
                i = hour_index(ms(r[0]))
                if 0 <= i < NH:
                    p_close[i] = float(r[4]); p_high[i] = float(r[2])
            for r in rows(cached(cache, f"data/spot/monthly/klines/{sp}/1h/{sp}-1h-{m}.zip")):
                i = hour_index(ms(r[0]))
                if 0 <= i < NH:
                    s_close[i] = float(r[4])
            for r in rows(cached(cache, f"data/futures/um/monthly/fundingRate/{p}/{p}-fundingRate-{m}.zip")):
                # calc_time, funding_interval_hours, last_funding_rate; settle at the start of that hour
                i = hour_index(ms(r[0]) // 3_600_000 * 3_600_000)
                if 0 <= i < NH:
                    fund[i] += float(r[2])
        return p, dict(s=s_close, p=p_close, ph=p_high, f=fund)
    with cf.ThreadPoolExecutor(16) as ex:
        for p, d in ex.map(fb, need):
            data[p] = d
    return data, rank


# ---------------------------------------------------------------- simulation

class Pos:
    __slots__ = ("sym", "su", "pu", "p0", "x", "t0", "pnl", "fund", "fees", "resets", "last_i", "cap")

    def __init__(self, sym, x, s, p, t0):
        self.sym, self.x, self.t0 = sym, x, t0
        self.su, self.pu, self.p0 = x / s, x / p, p
        self.pnl = self.fund = self.fees = 0.0
        self.resets, self.last_i, self.cap = 0, t0, 1.5 * x


def leg_cost(x):
    return x * (COSTS["spot_fee"] + COSTS["slip"]) + x * (COSTS["perp_fee"] + COSTS["slip"])


def value(pos, s, p):
    return pos.su * s + pos.pu * (pos.p0 - p)


class Book:
    """Shared position rules for the portfolio and for random twins."""

    def __init__(self, data):
        self.d = data

    def px(self, sym, i, pos=None):
        d = self.d[sym]
        s, p = d["s"][i], d["p"][i]
        ok = not (math.isnan(s) or math.isnan(p))
        return ok, s, p

    def open(self, sym, x, i):
        ok, s, p = self.px(sym, i)
        pos = Pos(sym, x, s, p, i)
        c = leg_cost(x)
        pos.fees += c
        return pos, -x - c       # cash change

    def close(self, pos, i, penalty=0.0, s=None, p=None):
        if s is None:
            ok, s, p = self.px(pos.sym, pos.last_i)
            ok2, s2, p2 = self.px(pos.sym, i)
            if ok2:
                s, p = s2, p2
        v = value(pos, s, p)
        c = leg_cost(pos.su * s) + penalty * pos.pu * p
        pos.fees += c
        return v - c             # cash change

    def step(self, pos, i):
        """Advance one hour. Returns (cash_change, event) where event in None/'reset'/'liq'/'delist'."""
        d = self.d[pos.sym]
        ok, s, p = self.px(pos.sym, i)
        if not ok:
            if i - pos.last_i > DELIST_GAP_H:
                return self.close(pos, pos.last_i, penalty=DELIST_PENALTY), "delist"
            return 0.0, None
        pos.last_i = i
        cash = 0.0
        f = d["f"][i]
        if f:
            g = pos.pu * p * f        # positive funding: short receives
            pos.fund += g
            cash += g
        ph = d["ph"][i]
        if not math.isnan(ph) and ph >= pos.p0 * (1 + LIQ_UP):
            pliq = pos.p0 * (1 + LIQ_UP)
            return cash + self.close(pos, i, penalty=LIQ_PENALTY, s=s, p=pliq), "liq"
        if p >= pos.p0 * (1 + RESET_UP):
            back = self.close(pos, i, s=s, p=p)
            x = pos.su * s
            c = leg_cost(x)
            pos.su, pos.pu, pos.p0, pos.x = x / s, x / p, p, x
            pos.fees += c
            pos.resets += 1
            return cash + back - x - c, "reset"
        return cash, None


def score(data, sym, i):
    f = data[sym]["f"]
    return f[max(0, i - 72) + 1:i + 1].sum() * 365 / 3


def month_of(i):
    t = datetime.fromtimestamp(H0.timestamp() + i * 3600, tz=timezone.utc)
    return f"{t.year:04d}-{t.month:02d}"


def first_valid(arr):
    nz = np.flatnonzero(~np.isnan(arr))
    return nz[0] if len(nz) else 10 ** 9


def eligible(data, rank, i, limit, ages):
    m = month_of(i)
    out = []
    for sym, k in rank.get(m, {}).items():
        if k < limit and sym in data and i - ages[sym] >= MIN_AGE_H:
            d = data[sym]
            if not (math.isnan(d["s"][i]) or math.isnan(d["p"][i])):
                out.append(sym)
    return out


def run_group(g, data, rank, ages):
    book = Book(data)
    i0 = hour_index(int(SIM_START.timestamp() * 1000))
    cash, held, trades = 1.0, {}, []
    eq = np.full(NH, np.nan)
    events = dict(reset=0, liq=0, delist=0)
    slots_used = []
    for i in range(i0, NH):
        # hourly position updates
        for sym in list(held):
            pos = held[sym]
            c, ev = book.step(pos, i)
            cash += c
            if ev:
                events[ev] += 1
            if ev in ("liq", "delist"):
                trades.append((sym, pos.t0, i, pos, ev))
                del held[sym]
        # daily rebalance at 00:00 UTC
        if (i % 24) == 0:
            equity = cash + sum(value(p, *book.px(p.sym, p.last_i)[1:]) for p in held.values())
            if g["mode"] == "fixed":
                cand = [s for s in g["syms"] if book.px(s, i)[0]]
                keep_ok = set(cand)
            else:
                cand = eligible(data, rank, i, UNIVERSE_N, ages)
                keep_ok = set(eligible(data, rank, i, UNIVERSE_HOLD, ages))
            sc = {s: score(data, s, i) for s in set(cand) | set(held)}
            for sym in list(held):
                if sym not in keep_ok or sc[sym] < g["t_out"]:
                    pos = held.pop(sym)
                    cash += book.close(pos, i)
                    trades.append((sym, pos.t0, i, pos, "rule"))
            # resize drifted positions back to target (price moves shrink or grow the hedged notional)
            target = equity / (g["n"] * 1.5)
            for sym in list(held):
                pos = held[sym]
                ok, s, p = book.px(sym, i)
                if ok and not (0.5 * target <= pos.su * s <= 1.5 * target):
                    cash += book.close(pos, i)
                    npos, c = book.open(sym, target, i)
                    npos.t0, npos.fund, npos.fees, npos.resets = pos.t0, pos.fund, pos.fees, pos.resets + 1
                    cash += c
                    held[sym] = npos
                    events["resize"] = events.get("resize", 0) + 1
            free = g["n"] - len(held)
            if free > 0:
                pick = sorted([s for s in cand if s not in held and sc[s] >= g["t_in"]], key=lambda s: -sc[s])[:free]
                for sym in pick:
                    x = equity / (g["n"] * 1.5)
                    pos, c = book.open(sym, x, i)
                    cash += c
                    held[sym] = pos
            slots_used.append(len(held))
        eq[i] = cash + sum(value(p, *book.px(p.sym, p.last_i)[1:]) for p in held.values())
    for sym, pos in held.items():
        trades.append((sym, pos.t0, NH - 1, pos, "end"))
        # left open at end: valued in eq already
    return eq, trades, events, float(np.mean(slots_used))


def trade_return(book, sym, t0, t1, x=1.0):
    """Standalone trade with the same rules; return pnl / capital (1.5x) and funding share."""
    pos, cash = book.open(sym, x, t0)
    for i in range(t0 + 1, t1 + 1):
        c, ev = book.step(pos, i)
        cash += c
        if ev in ("liq", "delist"):
            return cash / pos.cap, pos.fund / pos.cap
    cash += book.close(pos, t1)
    return cash / (1.5 * x), pos.fund / (1.5 * x)


def twins(g, trades, data, rank, ages, n=TWINS, seed=7):
    book = Book(data)
    rng = random.Random(seed)
    base = [trade_return(book, s, t0, t1)[0] for s, t0, t1, _, _ in trades if t1 > t0]
    means = []
    elig_cache = {}
    for _ in range(n):
        r = []
        for s, t0, t1, _, _ in trades:
            if t1 <= t0:
                continue
            if t0 not in elig_cache:
                elig_cache[t0] = eligible(data, rank, t0, UNIVERSE_N, ages)
            el = elig_cache[t0]
            r.append(trade_return(book, rng.choice(el), t0, t1)[0])
        means.append(float(np.mean(r)))
    return float(np.mean(base)), means, base


# ---------------------------------------------------------------- reporting

def stats(eq, a, b):
    seg = eq[a:b]
    seg = seg[~np.isnan(seg)]
    if len(seg) < 48:
        return {}
    daily = seg[::24]
    rets = np.diff(daily) / daily[:-1]
    yrs = len(seg) / (24 * 365)
    tot = seg[-1] / seg[0] - 1
    peak = np.maximum.accumulate(seg)
    dd = float(np.max(1 - seg / peak))
    sharpe = float(np.mean(rets) / np.std(rets) * math.sqrt(365)) if np.std(rets) > 0 else float("nan")
    mon = []
    for k in range(0, len(daily) - 30, 30):
        mon.append(daily[k + 30] / daily[k] - 1)
    return dict(total=tot, cagr=(1 + tot) ** (1 / yrs) - 1 if tot > -1 else -1, maxdd=dd, sharpe=sharpe,
                pos_months=float(np.mean(np.array(mon) > 0)) if mon else float("nan"),
                worst_month=float(min(mon)) if mon else float("nan"))


def regimes(eq, data):
    """Approximate six regimes per day: BTC 30d return up(>+10%)/flat/down(<-10%) x BTC 30d funding above/below median."""
    s = data["BTCUSDT"]["s"]
    f = data["BTCUSDT"]["f"]
    i0 = hour_index(int(SIM_START.timestamp() * 1000))
    days = list(range(i0, NH - 24, 24))
    fv = [f[i - 720:i].sum() for i in days]
    med = float(np.median(fv))
    out = {}
    for i, fsum in zip(days, fv):
        if np.isnan(eq[i]) or np.isnan(eq[i + 24]) or np.isnan(s[i]) or np.isnan(s[i - 720]):
            continue
        r = s[i] / s[i - 720] - 1
        trend = "up" if r > 0.10 else ("down" if r < -0.10 else "flat")
        key = f"{trend}/{'fundHigh' if fsum > med else 'fundLow'}"
        o = out.setdefault(key, [0, 0.0])
        o[0] += 1
        o[1] += math.log(eq[i + 24] / eq[i])
    return {k: dict(days=v[0], ann=math.exp(v[1] / v[0] * 365) - 1) for k, v in sorted(out.items())}


def per_year(eq):
    out = {}
    for y in range(2022, 2027):
        a = hour_index(int(datetime(max(y, 2022), 10 if y == 2022 else 1, 1, tzinfo=timezone.utc).timestamp() * 1000))
        b = min(NH - 1, hour_index(int(datetime(y + 1, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)))
        if a < b and not np.isnan(eq[a]) and not np.isnan(eq[b]):
            out[str(y)] = eq[b] / eq[a] - 1
    return out


def ci95(x):
    x = np.asarray(x)
    if len(x) < 2:
        return float("nan")
    return float(1.96 * np.std(x, ddof=1) / math.sqrt(len(x)))


def main():
    cache, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    logf = open(os.path.join(out, "log.txt"), "w")

    def log(s):
        print(s, flush=True)
        logf.write(s + "\n")
    if "--selftest" in sys.argv:
        data, rank = synthetic()
    else:
        data, rank = load(cache, log)
    ages = {s: first_valid(d["p"]) for s, d in data.items()}
    a = hour_index(int(SIM_START.timestamp() * 1000))
    sp = hour_index(int(SPLIT.timestamp() * 1000))
    report = {}
    groups = list(GROUPS)
    for g in groups + [dict(GROUPS[3], name="C4_top10_in15_cost3x", cost3=True)]:
        saved = dict(COSTS)
        if g.get("cost3"):
            COSTS["slip"] *= 3
        eq, trades, events, avg_slots = run_group(g, data, rank, ages)
        book = Book(data)
        fund = sum(p.fund for *_, p, _ in trades)
        fees = sum(p.fees for *_, p, _ in trades)
        r = dict(full=stats(eq, a, NH), design=stats(eq, a, sp), valid=stats(eq, sp, NH),
                 years=per_year(eq), regimes=regimes(eq, data), events=events, trades=len(trades),
                 avg_positions=avg_slots, funding_income=fund, fees=fees,
                 top_syms=sorted({}.fromkeys(s for s, *_ in trades).keys(),
                                 key=lambda s: -sum(1 for t in trades if t[0] == s))[:15])
        if g["mode"] == "universe" and not g.get("cost3"):
            m, tw, base = twins(g, trades, data, rank, ages)
            tw = np.array(tw)
            r["per_trade_mean"] = m
            r["per_trade_ci"] = ci95(base)
            r["twin_mean"] = float(np.mean(tw))
            r["twin_p95"] = float(np.percentile(tw, 95))
            r["beats_twins_share"] = float(np.mean(m > tw))
            # validation-only trades
            vt = [t for t in trades if t[1] >= sp]
            if vt:
                mv, twv, bv = twins(g, vt, data, rank, ages, n=TWINS // 3, seed=11)
                r["valid_per_trade_mean"] = mv
                r["valid_per_trade_ci"] = ci95(bv)
                r["valid_twin_mean"] = float(np.mean(twv))
                r["valid_beats_twins_share"] = float(np.mean(mv > np.array(twv)))
        COSTS.clear(); COSTS.update(saved)
        report[g["name"]] = r
        log(f"== {g['name']}\n" + json.dumps(r, indent=1, default=float))
        np.save(os.path.join(out, f"eq_{g['name']}.npy"), eq[a:])
    json.dump(report, open(os.path.join(out, "report.json"), "w"), indent=1, default=float)
    log("DONE")


# ---------------------------------------------------------------- self test

def synthetic():
    rng = np.random.default_rng(1)
    data, rank = {}, {}
    syms = ["BTCUSDT", "ETHUSDT"] + [f"C{k}USDT" for k in range(30)]
    for k, s in enumerate(syms):
        ret = rng.normal(0, 0.01, NH)
        px = 100 * np.exp(np.cumsum(ret))
        f = np.zeros(NH)
        f[::8] = rng.normal(0.0001 * (k % 5), 0.0001, len(f[::8]))
        d = dict(s=px.copy(), p=px * 1.0005, ph=px * 1.003, f=f)
        if s == "C3USDT":
            d["s"][20000:] = np.nan; d["p"][20000:] = np.nan
        data[s] = d
    for m in MONTHS:
        rank[m] = {s: k for k, s in enumerate(syms)}
    return data, rank


if __name__ == "__main__":
    main()
