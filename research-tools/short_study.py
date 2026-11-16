#!/usr/bin/env python3
"""Research only (2026-11-16): simulated "hold BTC + short altcoin perpetuals" overlays. Pure simulation on public data;
nothing here places orders, and the project stays shadow-only.

Data: research-data/binance-um branch (binance-um-1d.csv.gz, binance-um-funding.csv.gz), Binance USDT-M perpetuals.
Rules written down before looking at results:
  * pool(d): perpetuals except BTC, stablecoins and index contracts, listed >= 30 days, ranked by 30-day median quote volume.
  * the account holds BTC worth its whole equity (the user holds BTC anyway) and adds short positions with a total
    notional of 50% of equity; daily P&L of a short = -(price change) + funding (a short receives positive funding).
  * cost 0.10% of traded notional per side (taker fee 0.05% + slippage 0.05%).
  * every short is closed if its daily close reaches 2x its entry price (stop, no re-entry until the next rebalance).
  * in-sample to 2022-12-31, out-of-sample after; results in BTC terms (NAV / BTC price): 1.00 = same as just holding BTC.
  A: short the 20 most liquid alts, equal notional, monthly rebalance.
  B: short each newly listed perpetual from the close of its 7th day for 90 days, 2% of equity each, total capped at 50%.
  C: short the 10 "highest MAX" (largest single-day gain in the last 30 days) of the top 50, monthly; 1,000 random twins
     short 10 random coins of the same top 50.
usage: short_study.py dataDir outFile
"""
import os, sys
import numpy as np
import pandas as pd

NR = int(os.environ.get('NR', '1000'))
RNG = 20261116
IS_END = pd.Timestamp('2022-12-31')
START = pd.Timestamp('2020-07-01')
COST = 0.001
LEV = 0.5
STOP = 2.0
SKIP = {'BTCUSDT', 'USDCUSDT', 'BTCDOMUSDT', 'DEFIUSDT', 'FOOTBALLUSDT', 'BLUEBIRDUSDT', 'BTCSTUSDT', 'TUSDUSDT', 'FDUSDUSDT', 'BUSDUSDT'}
out_lines = []


def P(*a):
    s = ' '.join(str(x) for x in a)
    print(s, flush=True)
    out_lines.append(s)


def tbl(df):
    P(df.to_markdown(index=False))
    P('')


class Data:
    def __init__(self, d):
        df = pd.read_csv(d + '/binance-um-1d.csv.gz')
        df = df[~df.sym.str.contains('_') & df.sym.str.endswith('USDT')]
        df['date'] = pd.to_datetime(df.day, unit='D')
        self.dates = pd.date_range(df.date.min(), df.date.max(), freq='D')
        C = df.pivot(index='date', columns='sym', values='c').reindex(self.dates)
        V = df.pivot(index='date', columns='sym', values='qv').reindex(self.dates)
        H = df.pivot(index='date', columns='sym', values='h').reindex(self.dates)
        self.syms = list(C.columns)
        self.n = len(self.syms)
        self.T = len(self.dates)
        self.btc = self.syms.index('BTCUSDT')
        self.alt = np.array([s not in SKIP for s in self.syms])
        raw = C.values.astype(float)
        self.has = raw > 0
        # a contract that stops for more than 3 days and returns is treated as a new one (no return across the gap)
        self.first = np.array([np.argmax(self.has[:, i]) if self.has[:, i].any() else 10 ** 9 for i in range(self.n)])
        self.listed = self.first.copy()
        for i in range(self.n):
            idx = np.where(self.has[:, i])[0]
            for k in np.where(np.diff(idx) > 3)[0]:
                a, b = idx[k], idx[k + 1]
                raw[b:, i] *= raw[a, i] / raw[b, i]
                self.first[i] = b
        self.c = pd.DataFrame(raw).ffill().values
        self.hi = np.where(self.has, H.values.astype(float) / C.values.astype(float), 1.0)  # day high / close
        with np.errstate(divide='ignore', invalid='ignore'):
            r = self.c[1:] / self.c[:-1] - 1
        self.r = np.nan_to_num(np.vstack([np.zeros((1, self.n)), r]))
        f = pd.read_csv(d + '/binance-um-funding.csv.gz')
        f['date'] = pd.to_datetime(f.day, unit='D')
        F = f.pivot(index='date', columns='sym', values='rate').reindex(self.dates).reindex(columns=self.syms)
        self.f = np.nan_to_num(F.values.astype(float))
        v = np.where(self.has, V.values.astype(float), 0.0)
        self.medv = pd.DataFrame(v).rolling(30).median().values
        self.pools = []
        for d in range(self.T):
            ok = self.alt & self.has[d] & (d - self.first >= 30) & (self.medv[d] > 0)
            idx = np.where(ok)[0]
            self.pools.append(idx[np.argsort(-self.medv[d, idx], kind='stable')])

    def day(self, ts):
        return int((pd.Timestamp(ts) - self.dates[0]).days)


def run(D, d0, d1, decide, hold_btc=True):
    """decide(d, equity, pos) -> list of (i, target_notional or None to close) applied at the close of d.
    Returns NAV, and a stats dict."""
    E = 1.0
    pos = {}  # i -> [units, entry_px, entry_day]
    nav = np.ones(d1 - d0 + 1)
    st = {'stops': 0, 'funding': 0.0, 'price': 0.0, 'cost': 0.0, 'notional_days': 0.0, 'trades': [], 'btc': 0.0}
    for d in range(d0, d1 + 1):
        if d > d0:
            if hold_btc:
                g = E * D.r[d, D.btc]
                E += g
                st['btc'] += g
            for i, (u, px, ed) in pos.items():
                pp = -u * (D.c[d, i] - D.c[d - 1, i])
                ff = u * D.c[d - 1, i] * D.f[d, i]
                E += pp + ff
                st['price'] += pp
                st['funding'] += ff
                st['notional_days'] += u * D.c[d - 1, i]

        def close(i, why):
            nonlocal E
            u, px, ed = pos.pop(i)
            fee = u * D.c[d, i] * COST
            E -= fee
            st['cost'] += fee
            st['trades'].append((D.syms[i], D.dates[ed], D.dates[d], 1 - D.c[d, i] / px, why))

        for i in list(pos):
            if D.c[d, i] >= STOP * pos[i][1]:
                st['stops'] += 1
                close(i, 'stop')
            elif not D.has[d:, i].any():
                close(i, 'delisted')
        for i, tgt in decide(d, E, pos) or []:
            if tgt is None or tgt <= 0:
                if i in pos:
                    close(i, 'rule')
                continue
            px = D.c[d, i]
            cur = pos[i][0] * px if i in pos else 0.0
            fee = abs(tgt - cur) * COST
            E -= fee
            st['cost'] += fee
            if i in pos:
                pos[i][0] = tgt / px
            else:
                pos[i] = [tgt / px, px, d]
        nav[d - d0] = E
        if E <= 0:
            nav[d - d0:] = 0
            break
    return nav, st


def maxdd(x):
    x = np.asarray(x)
    return float((1 - x / np.maximum.accumulate(x)).max())


def seg(D, x, d0, a, b):
    a, b = max(D.day(a), d0), min(D.day(b), d0 + len(x) - 1)
    return x[b - d0] / x[a - d0] - 1 if b > a else np.nan


def summary(D, name, nav, st, d0):
    btc = D.c[d0:d0 + len(nav), D.btc]
    nb = nav / (btc / btc[0])
    end = D.dates[d0 + len(nav) - 1]
    yrs = len(nav) / 365
    row = {'方案': name, '全期 BTC 倍数': round(nb[-1], 2), 'BTC 计最大回撤': f'{maxdd(nb):.0%}', 'USDT 倍数': round(nav[-1], 2), 'USDT 最大回撤': f'{maxdd(nav):.0%}',
           '样本内 BTC 倍数': round(1 + seg(D, nb, d0, START, IS_END), 2), '样本外 BTC 倍数': round(1 + seg(D, nb, d0, IS_END, end), 2)}
    for y in range(2020, 2027):
        row[str(y)] = f"{seg(D, nb, d0, f'{y - 1}-12-31', f'{y}-12-31'):+.0%}"
    d = np.diff(nb) / nb[:-1]
    row['BTC 计最差单日'] = f'{d.min():+.1%}'
    m = pd.Series(nb, index=D.dates[d0:d0 + len(nav)]).resample('ME').last().pct_change().dropna()
    row['最差单月'] = f'{m.min():+.0%}'
    row['亏钱月份占比'] = f'{(m < 0).mean():.0%}'
    if st:
        nd = st['notional_days'] / 365
        row['资金费年化(占名义)'] = f"{st['funding'] / nd:+.1%}" if nd else '-'
        row['触发翻倍止损'] = st['stops']
    return row


def month_starts(D, d0, d1):
    return [d for d in range(d0, d1 + 1) if D.dates[d].day == 1]


def basket(D, days, pick, n):
    days = set(days)

    def decide(d, E, pos):
        if d not in days:
            return None
        sel = [int(i) for i in pick(d)]
        out = [(i, None) for i in pos if i not in sel]
        return out + [(i, LEV * E / n) for i in sel]

    return decide


def study_a(D, d0, d1):
    P('## A：拿着 BTC，同时做空流动性前 20 的山寨永续（等额，每月调整）')
    ms = month_starts(D, d0, d1) + [d0]
    rows = []
    nav, st = run(D, d0, d1, basket(D, ms, lambda d: D.pools[d][:20], 20))
    rows.append(summary(D, 'BTC + 做空前 20 山寨（名义 50%）', nav, st, d0))
    a_nav, a_st = nav, st
    nav, st = run(D, d0, d1, basket(D, ms, lambda d: D.pools[d][:20], 20), hold_btc=False)
    rows.append(summary(D, '只做空前 20 山寨（不拿 BTC，USDT 保证金）', nav, st, d0))
    nav, st = run(D, d0, d1, lambda d, E, pos: None)
    rows.append(summary(D, '只拿 BTC', nav, None, d0))
    tbl(pd.DataFrame(rows))
    t = pd.DataFrame(a_st['trades'], columns=['sym', 'in', 'out', 'pnl', 'why'])
    P(f"盈亏拆分（BTC + 做空前 20，按起始权益 1 计）：BTC 涨跌 {a_st['btc']:+.2f}，做空的价格盈亏 {a_st['price']:+.2f}，资金费 {a_st['funding']:+.2f}，手续费 {-a_st['cost']:+.2f}。"
      f"被止损的仓位 {a_st['stops']} 个：{', '.join(t[t.why == 'stop'].sym.head(15))}。")
    P('')
    return a_nav


def study_b(D, d0, d1):
    P('## B：拿着 BTC，做空每个新上线的永续（上线第 7 天收盘开空，持有 90 天，每个占权益 2%，合计不超过 50%）')
    start = {}
    for i in range(D.n):
        f = D.listed[i]
        if D.alt[i] and f + 7 >= d0 and f + 7 <= d1 and f > 30:
            start.setdefault(f + 7, []).append(i)

    def decide(d, E, pos):
        out = [(i, None) for i, (u, px, ed) in pos.items() if d - ed >= 90]
        used = sum(u * D.c[d, i] for i, (u, px, ed) in pos.items() if d - ed < 90)
        for i in start.get(d, []):
            if D.has[d, i] and used + 0.02 * E <= LEV * E:
                out.append((i, 0.02 * E))
                used += 0.02 * E
        return out

    nav, st = run(D, d0, d1, decide)
    tbl(pd.DataFrame([summary(D, 'BTC + 做空新上线永续', nav, st, d0)]))
    t = pd.DataFrame(st['trades'], columns=['sym', 'in', 'out', 'pnl', 'why'])
    t['year'] = t['in'].dt.year
    rows = []
    for y, g in list(t.groupby('year')) + [('全部', t)]:
        rows.append({'开仓年份': y, '笔数': len(g), '赚钱占比': f'{(g.pnl > 0).mean():.0%}', '每笔中位': f'{g.pnl.median():+.0%}', '每笔平均': f'{g.pnl.mean():+.0%}',
                     '被翻倍止损': int((g.why == 'stop').sum()), '最差一笔': f'{g.pnl.min():+.0%}'})
    P('每笔做空（价格盈亏占开仓名义，不含资金费；+ 表示币跌了、做空赚）：')
    tbl(pd.DataFrame(rows))
    # how bad can a squeeze get before the stop: worst close-to-close and intraday excursion over the life of each short
    worst = []
    for sym, a, b, pnl, why in st['trades']:
        i = D.syms.index(sym)
        da, db = D.day(a), D.day(b)
        path = D.c[da:db + 1, i] / D.c[da, i]
        worst.append(((path * D.hi[da:db + 1, i]).max() - 1, np.diff(path).max() / 1 if len(path) > 1 else 0, sym, a.date()))
    w = sorted(worst, reverse=True)[:8]
    P('持仓期间涨得最猛的 8 个（盘中最高相对开仓价）：' + '；'.join(f'{s} {d} +{x:.0%}' for x, _, s, d in w))
    P(f'单日收盘涨幅超过 50% 的持仓日：{sum(1 for x in worst if x[1] > 0.5)} 次。')
    P('')


def study_c(D, d0, d1):
    P('## C：拿着 BTC，做空"刚暴涨过"的 10 个（前 50 里过去 30 天最大单日涨幅最高的），每月调整')
    ms = month_starts(D, d0, d1) + [d0]

    def feat(d, hi=True):
        p = D.pools[d][:50]
        w = D.r[d - 29:d + 1][:, p]
        o = np.argsort(w.max(0), kind='stable')
        return p[o[-10:]] if hi else p[o[:10]]

    rows = []
    nav, st = run(D, d0, d1, basket(D, ms, feat, 10))
    rng = np.random.default_rng(RNG)
    rnd = []
    for _ in range(NR):
        picks = {d: rng.choice(D.pools[d][:50], 10, replace=False) for d in ms}
        rnd.append(run(D, d0, d1, basket(D, ms, lambda d: picks[d], 10))[0])
    rnd = np.array(rnd)
    isd = D.day(IS_END) - d0
    row = summary(D, 'BTC + 做空高 MAX 10 个', nav, st, d0)
    row['随机分位(全期)'] = f'{(rnd[:, -1] < nav[-1]).mean():.0%}'
    row['随机分位(样本内)'] = f'{(rnd[:, isd] < nav[isd]).mean():.0%}'
    row['随机分位(样本外)'] = f'{(rnd[:, -1] / rnd[:, isd] < nav[-1] / nav[isd]).mean():.0%}'
    btc = D.c[d0:d1 + 1, D.btc]
    row['随机中位 BTC 倍数'] = round(float(np.median(rnd[:, -1])) / (btc[-1] / btc[0]), 2)
    rows.append(row)
    nav2, st2 = run(D, d0, d1, basket(D, ms, lambda d: feat(d, False), 10))
    rows.append(summary(D, 'BTC + 做空低 MAX 10 个（镜像）', nav2, st2, d0))
    tbl(pd.DataFrame(rows))
    P(f'随机做空 10 个：BTC 倍数的 5% / 50% / 95% 分位 = ' + ' / '.join(f'{np.percentile(rnd[:, -1], q) / (btc[-1] / btc[0]):.2f}' for q in (5, 50, 95))
      + f'；{(rnd.min(1) <= 0.3).mean():.0%} 的随机组合期间权益曾跌到 0.3 以下（USDT 计）。')
    P('')


def stress(D, d0, d1):
    P('## 压力情景：山寨整体暴涨时做空篮子会怎样')
    p = [D.pools[d][:20] for d in range(D.T)]
    b = np.array([D.r[d, p[d - 1]].mean() if d > 0 and len(p[d - 1]) else 0 for d in range(D.T)])
    s = pd.Series(b, index=D.dates)[D.dates[d0]:]
    P(f'前 20 山寨永续等权篮子：最大单日涨幅 {s.max():+.0%}（{s.idxmax().date()}），最大 7 天涨幅 {((1 + s).rolling(7).apply(np.prod) - 1).max():+.0%}，'
      f'最大 30 天涨幅 {((1 + s).rolling(30).apply(np.prod) - 1).max():+.0%}，最大 90 天涨幅 {((1 + s).rolling(90).apply(np.prod) - 1).max():+.0%}。')
    x = ((1 + s).rolling(30).apply(np.prod) - 1)
    P('30 天涨幅超过 50% 的月份：' + ', '.join(sorted({str(t)[:7] for t in x[x > 0.5].index})))
    P('')


def main():
    D = Data(sys.argv[1])
    d0, d1 = D.day(START), D.T - 1
    P(f'# 做空山寨模拟结果（short_study.py 自动生成，纯模拟）\n\n数据：Binance U 本位永续日线和资金费 {D.dates[0].date()} ～ {D.dates[-1].date()}，合约 {D.n} 个。'
      f'评估 {START.date()} ～ {D.dates[-1].date()}；样本内到 {IS_END.date()}。BTC 倍数 1.00 = 和只拿 BTC 一样。\n')
    study_a(D, d0, d1)
    study_b(D, d0, d1)
    study_c(D, d0, d1)
    stress(D, d0, d1)
    open(sys.argv[2], 'w', encoding='utf-8').write('\n'.join(out_lines) + '\n')


main()
