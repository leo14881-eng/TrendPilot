#!/usr/bin/env python3
"""Research only (2026-11-16): eight new altcoin ideas on Binance daily bars, written down before looking at results.

Data: research-data/binance-1d branch (binance-1d.csv.gz, stablecoins.json.gz), all USDT pairs incl. delisted.
Common rules (fixed in advance):
  * pool(d): alts (no BTC, stablecoins, fiat, wrapped, leveraged tokens), listed >= 30 days, ranked by the median
    quote volume of the last 30 days up to d; decisions use closes up to d and fill at the close of d.
  * cost per side: 0.15% for pool rank <= 40, 0.30% beyond (fee 0.1% + slippage).
  * in-sample 2020-01-01..2022-12-31, out-of-sample 2023-01-01..end; both always reported.
  * every selection rule has 1,000 random twins (same days, same count, same pool, same costs).
  * money is measured in USDT and in BTC (NAV / BTC price) because profits end up in BTC.

usage: idea_study.py dataDir outFile
"""
import gzip, json, sys
import numpy as np
import pandas as pd

RNG = 20261116
import os
NR = int(os.environ.get("NR", "1000"))
IS_END = pd.Timestamp('2022-12-31')
START = pd.Timestamp('2020-01-01')
STABLE = {'USDC', 'BUSD', 'TUSD', 'USDP', 'DAI', 'FDUSD', 'PAX', 'SUSD', 'UST', 'USTC', 'EUR', 'GBP', 'AUD', 'EURI', 'AEUR',
          'PAXG', 'WBTC', 'BETH', 'WBETH', 'USDS', 'USDSB', 'USD1', 'XUSD', 'BFUSD', 'USDE', 'EURC', 'BKRW', 'IDRT', 'BIDR',
          'NGN', 'RUB', 'TRY', 'ZAR', 'UAH', 'BRL', 'BVND', 'VAI', 'SUSDT', 'USDSUSDT', 'RLUSD', 'U', 'WBNB', 'BNSOL', 'WSTETH', 'RWUSD', 'KGST', 'XAUT', 'BULL', 'BEAR'}
out_lines = []


def P(*a):
    s = ' '.join(str(x) for x in a)
    print(s, flush=True)
    out_lines.append(s)


def tbl(df):
    P(df.to_markdown(index=False) if hasattr(df, 'to_markdown') else df.to_string(index=False))
    P('')


# ---------------------------------------------------------------- data
class Data:
    def __init__(self, d):
        df = pd.read_csv(d + '/binance-1d.csv.gz')
        df['date'] = pd.to_datetime(df.day, unit='D')
        self.dates = pd.date_range(df.date.min(), df.date.max(), freq='D')
        piv = lambda c: df.pivot(index='date', columns='sym', values=c).reindex(self.dates)
        C, V, H, L, O = piv('c'), piv('qv'), piv('h'), piv('l'), piv('o')
        syms = list(C.columns)
        allb = {s[:-4] for s in syms}

        tok_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'universe', 'tokenized-symbols.txt')
        tok = {x.strip().upper() for x in open(tok_file, encoding='utf-8') if x.strip() and not x.startswith('#')}
        tok |= {t + 'USDT' for t in tok} | {t[:-4] for t in tok if t.endswith('USDT')}

        def bad(s):
            b = s[:-4]
            if b in STABLE or b in tok or s in tok:
                return True
            for suf in ('UP', 'DOWN', 'BULL', 'BEAR'):
                if b.endswith(suf) and b[:-len(suf)] in allb and len(b) > len(suf):
                    return True
            return False

        self.syms = syms
        self.n = len(syms)
        self.btc = syms.index('BTCUSDT')
        self.alt = np.array([not bad(s) and s != 'BTCUSDT' for s in syms])
        self.raw = C.values.astype(float)
        self.has = self.raw > 0
        # A ticker that stops for more than 3 days and comes back (LUNA, FTT ...) is a different instrument: the new
        # segment is rescaled to continue from the last close (zero return across the gap) and its age restarts.
        raw = self.raw.copy()
        relist = {}
        for i in range(raw.shape[1]):
            idx = np.where(self.has[:, i])[0]
            if len(idx) < 2:
                continue
            for k in np.where(np.diff(idx) > 3)[0]:
                a, b = idx[k], idx[k + 1]
                raw[b:, i] *= raw[a, i] / raw[b, i]
                relist[i] = b
        self.c = pd.DataFrame(raw).ffill().values.astype(float)  # carried close; NaN before listing
        self.v = np.where(self.has, V.values.astype(float), 0.0)
        self.h, self.l, self.o = H.values.astype(float), L.values.astype(float), O.values.astype(float)
        self.T = len(self.dates)
        self.first = np.array([np.argmax(self.has[:, i]) if self.has[:, i].any() else 10 ** 9 for i in range(self.n)])
        self.listed = self.first.copy()  # true first listing (for the listing study)
        for i, b in relist.items():
            self.first[i] = b
        self.last = np.array([self.T - 1 - np.argmax(self.has[::-1, i]) if self.has[:, i].any() else -1 for i in range(self.n)])
        with np.errstate(divide='ignore', invalid='ignore'):
            r = self.c[1:] / self.c[:-1] - 1
        self.r = np.vstack([np.full((1, self.n), np.nan), r])  # r[d] = close d / close d-1 - 1 (0 after delisting)
        self.medv = pd.DataFrame(self.v).rolling(30).median().values
        self.rank = np.full((self.T, self.n), 10 ** 6)
        self.pools = []
        for d in range(self.T):
            ok = self.alt & self.has[d] & (d - self.first >= 30) & (self.medv[d] > 0)
            idx = np.where(ok)[0]
            idx = idx[np.argsort(-self.medv[d, idx], kind='stable')]
            self.rank[d, idx] = np.arange(len(idx))
            self.pools.append(idx)
        self.btcpx = self.c[:, self.btc]

    def day(self, ts):
        return int((pd.Timestamp(ts) - self.dates[0]).days)

    def pool(self, d, n):
        return self.pools[d][:n]

    def cost(self, i, d):
        return 0.0015 if self.rank[d, i] < 40 else 0.0030


# ---------------------------------------------------------------- helpers
def run_weights(D, d0, d1, chooser):
    """Daily NAV (USDT) of a portfolio whose target weights are set by chooser(d) -> {idx: w} or None (= keep).
    Missing weight is cash. Costs are charged on traded value."""
    nav = np.ones(d1 - d0 + 1)
    hold = {}  # idx -> value
    cash = 1.0
    turn = 0.0
    for d in range(d0, d1 + 1):
        if d > d0:
            for i in list(hold):
                x = D.r[d, i]
                hold[i] *= 1 + (x if np.isfinite(x) else 0.0)
        tot = cash + sum(hold.values())
        tgt = chooser(d)
        if tgt is not None:
            fee = 0.0
            for i in set(hold) | set(tgt):
                dv = abs(tgt.get(i, 0.0) * tot - hold.get(i, 0.0))
                fee += dv * D.cost(i, d)
                turn += dv / tot
            tot -= fee
            hold = {i: w * tot for i, w in tgt.items() if w > 0}
            cash = tot - sum(hold.values())
        nav[d - d0] = tot
    return nav, turn


def maxdd(x):
    x = np.asarray(x)
    return float((1 - x / np.maximum.accumulate(x)).max())


def seg(D, nav, d0, a, b):
    a, b = max(D.day(a), d0), min(D.day(b), d0 + len(nav) - 1)
    if b <= a:
        return np.nan
    return nav[b - d0] / nav[a - d0] - 1


def summary(D, name, nav, d0, turn=np.nan):
    btc = D.btcpx[d0:d0 + len(nav)]
    nb = nav / (btc / btc[0])
    end = D.dates[d0 + len(nav) - 1]
    row = {'方案': name, '全期 USDT 倍数': round(nav[-1], 2), '全期 BTC 倍数': round(nb[-1], 2),
           'USDT 最大回撤': f'{maxdd(nav):.0%}', 'BTC 计最大回撤': f'{maxdd(nb):.0%}',
           '样本内 BTC 倍数': round(1 + seg(D, nb, d0, START, IS_END), 2), '样本外 BTC 倍数': round(1 + seg(D, nb, d0, IS_END, end), 2)}
    for y in range(2020, 2027):
        row[str(y)] = f"{seg(D, nb, d0, f'{y - 1}-12-31', f'{y}-12-31'):+.0%}"
    if np.isfinite(turn):
        row['年换手'] = round(turn / (len(nav) / 365), 1)
    return row


def pct_rank(x, rand):
    return float((np.asarray(rand) < x).mean())


def month_starts(D, d0, d1):
    return [d for d in range(d0, d1 + 1) if D.dates[d].day == 1]


def sundays(D, d0, d1):
    return [d for d in range(d0, d1 + 1) if D.dates[d].dayofweek == 6]


def periodic(D, d0, d1, days, pick, n):
    """Equal weight of pick(d) on each day in days; random twins pick n random coins from the same candidate set."""
    days = set(days)

    def ch(d):
        if d not in days:
            return None
        s = pick(d)
        return {int(i): 1.0 / n for i in s}

    return run_weights(D, d0, d1, ch)


def random_twins(D, d0, d1, days, cand, n, counts=None, reps=NR):
    rng = np.random.default_rng(RNG)
    days_l = sorted(days)
    res = []
    for _ in range(reps):
        picks = {}
        for d in days_l:
            c = cand(d)
            k = n if counts is None else counts[d]
            picks[d] = rng.choice(c, size=min(k, len(c)), replace=False) if len(c) and k else []
        nav, _ = periodic(D, d0, d1, days_l, lambda d: picks[d], n)
        res.append(nav)
    return np.array(res)


def twin_cols(D, row, nav, rnd, d0):
    btc = D.btcpx[d0:d0 + nav.shape[0]]
    isd = min(D.day(IS_END), d0 + len(nav) - 1) - d0
    row['随机分位(全期)'] = f'{pct_rank(nav[-1], rnd[:, -1]):.0%}'
    row['随机分位(样本内)'] = f'{pct_rank(nav[isd], rnd[:, isd]):.0%}'
    row['随机分位(样本外)'] = f'{pct_rank(nav[-1] / nav[isd], rnd[:, -1] / rnd[:, isd]):.0%}'
    row['随机中位 BTC 倍数'] = round(float(np.median(rnd[:, -1])) / (btc[-1] / btc[0]), 2)
    return row


# ---------------------------------------------------------------- I1 rebalancing premium
def idea1(D, d0, d1):
    P('## 思路 1：BTC + 山寨篮子的再平衡（不预测方向，靠波动低买高卖）')
    P('规则：50% BTC + 50% 流动性前 10 山寨等权；每月 1 日换成分并恢复目标权重；月内任一持仓权重相对目标偏离超过 ±30% 就恢复目标权重。'
      '对照：一直拿 BTC；同一篮子每月只换成分、不恢复权重（看再平衡本身贡献多少）；只按月恢复、不看偏离。')
    ms = set(month_starts(D, d0, d1)) | {d0}
    state = {}

    def target(d):
        al = D.pool(d, 10)
        t = {D.btc: 0.5}
        for i in al:
            t[int(i)] = 0.5 / len(al)
        return t

    def make(mode):
        st = {'t': None, 'w': None}

        def ch(d):
            if d in ms:
                st['t'] = target(d)
                if mode == 'nohold' and st['w'] is not None:
                    # keep drifted weights of survivors; proceeds of dropped coins go equally to new members
                    w = st['w']
                    keep = {i: w[i] for i in w if i in st['t']}
                    free = 1 - sum(keep.values())
                    new = [i for i in st['t'] if i not in keep]
                    if new:
                        for i in new:
                            keep[i] = free / len(new)
                    else:
                        s = sum(keep.values())
                        keep = {i: x / s for i, x in keep.items()}
                    st['w'] = dict(keep)
                    return dict(keep)
                st['w'] = dict(st['t'])
                return dict(st['t'])
            # drift
            w = st['w']
            g = {i: w[i] * (1 + (D.r[d, i] if np.isfinite(D.r[d, i]) else 0.0)) for i in w}
            s = sum(g.values())
            w = {i: x / s for i, x in g.items()}
            st['w'] = w
            if mode == 'band' and any(abs(w[i] / st['t'][i] - 1) > 0.30 for i in w):
                st['w'] = dict(st['t'])
                return dict(st['t'])
            return None

        return ch

    rows = []
    for name, mode in [('再平衡（±30% 偏离）', 'band'), ('只按月恢复权重', 'month'), ('只换成分不恢复权重', 'nohold')]:
        nav, turn = run_weights(D, d0, d1, make(mode))
        rows.append(summary(D, name, nav, d0, turn))
        state[mode] = nav
    btc = D.btcpx[d0:d1 + 1] / D.btcpx[d0]
    rows.append(summary(D, '一直拿 BTC', btc, d0, 0))
    alt, turn = run_weights(D, d0, d1, lambda d: ({int(i): 0.1 for i in D.pool(d, 10)} if d in ms else None))
    rows.append(summary(D, '只拿前 10 山寨（月度等权）', alt, d0, turn))
    tbl(pd.DataFrame(rows))
    return state


# ---------------------------------------------------------------- I2 low-MAX / low-vol / I8 volume shock
def idea2(D, d0, d1):
    P('## 思路 2：避开"彩票型"币（低 MAX / 低波动），以及镜像（高 MAX）')
    P('规则：每月 1 日，在流动性前 50 里按过去 30 天"最大单日涨幅"排序，买最低的 10 个等权持有 1 个月；低波动 = 30 天日收益标准差最低的 10 个；'
      '高 MAX = 最高的 10 个（镜像，用来看方向）。对照：前 50 等权、同日随机 10 个 ×1000。')
    ms = month_starts(D, d0, d1)
    r = D.r

    def feat(d, kind):
        p = D.pool(d, 50)
        w = r[d - 29:d + 1][:, p]
        ok = np.isfinite(w).sum(0) >= 25
        p, w = p[ok], w[:, ok]
        f = np.nanmax(w, 0) if 'MAX' in kind else np.nanstd(w, 0)
        o = np.argsort(f, kind='stable')
        return p[o[:10]] if kind.startswith('低') else p[o[-10:]]

    rnd = random_twins(D, d0, d1, ms, lambda d: D.pool(d, 50), 10)
    rows = []
    for kind in ['低 MAX', '低波动', '高 MAX']:
        nav, turn = periodic(D, d0, d1, ms, lambda d, k=kind: feat(d, k), 10)
        rows.append(twin_cols(D, summary(D, kind + ' 10 个', nav, d0, turn), nav, rnd, d0))
    nav, turn = periodic(D, d0, d1, ms, lambda d: D.pool(d, 50), 50)
    rows.append(summary(D, '前 50 等权', nav, d0, turn))
    tbl(pd.DataFrame(rows))


def idea8(D, d0, d1):
    P('## 思路 8：放量但价格没动（关注度先到、价格后到）')
    P('规则：每周日，在流动性前 100 里算"近 7 天日均成交额 ÷ 近 60 天日均成交额"，只看近 7 天涨跌幅绝对值 < 10% 的币，买比值最高的 10 个等权持有 1 周。'
      '对照：同一批候选（7 天涨跌 < 10%）里随机 10 个 ×1000。')
    ss = sundays(D, d0, d1)
    v = pd.DataFrame(D.v)
    v7, v60 = v.rolling(7).mean().values, v.rolling(60).mean().values

    def cand(d):
        p = D.pool(d, 100)
        ret7 = D.c[d, p] / D.c[d - 7, p] - 1
        ok = np.isfinite(ret7) & (np.abs(ret7) < 0.10) & (v60[d, p] > 0) & (d - D.first[p] >= 60)
        return p[ok]

    def pick(d):
        p = cand(d)
        ratio = v7[d, p] / v60[d, p]
        return p[np.argsort(-ratio, kind='stable')[:10]]

    nav, turn = periodic(D, d0, d1, ss, pick, 10)
    rnd = random_twins(D, d0, d1, ss, cand, 10)
    tbl(pd.DataFrame([twin_cols(D, summary(D, '放量未涨 10 个（周）', nav, d0, turn), nav, rnd, d0)]))


# ---------------------------------------------------------------- I3 listing life cycle
def idea3(D):
    P('## 思路 3：新币上线后的生命周期（事件研究，用来决定要不要避开新币）')
    P('规则：每个 2019-04 以后上线的山寨，从上线第 1 个日收盘买入，统计持有 7 / 30 / 90 / 180 / 365 天相对 BTC 的超额收益（对数收益差换回百分比），'
      '期间下架的按最后价格算到底。按上线年份分组。')
    rows = []
    recs = []
    for i in range(D.n):
        f = D.listed[i]
        if not D.alt[i] or f < 90 or f >= D.T or D.dates[f] < pd.Timestamp('2019-04-01'):
            continue
        rec = {'year': D.dates[f].year}
        for h in (7, 30, 90, 180, 365):
            if f + h < D.T:
                rec[h] = (D.c[f + h, i] / D.c[f, i]) / (D.btcpx[f + h] / D.btcpx[f]) - 1
        recs.append(rec)
    df = pd.DataFrame(recs)
    for y, g in list(df.groupby('year')) + [('全部', df)]:
        row = {'上线年份': y, '币数': len(g)}
        for h in (7, 30, 90, 180, 365):
            x = g[h].dropna() if h in g else pd.Series(dtype=float)
            row[f'{h}天 中位'] = f'{x.median():+.0%}' if len(x) else '-'
            row[f'{h}天 跑输BTC占比'] = f'{(x < 0).mean():.0%}' if len(x) else '-'
        rows.append(row)
    tbl(pd.DataFrame(rows))
    x = df[180].dropna()
    P(f'180 天：平均 {x.mean():+.1%}，中位 {x.median():+.1%}，跑输 BTC 的占 {(x < 0).mean():.0%}（{len(x)} 个币）。')
    # effect of excluding young coins on the pool itself: forward 30d excess of pool members by age bucket
    recs = []
    for d in range(D.day(START), D.T - 30, 7):
        p = D.pool(d, 100)
        fwd = (D.c[d + 30, p] / D.c[d, p]) / (D.btcpx[d + 30] / D.btcpx[d]) - 1
        age = d - D.first[p]
        for a, f_ in zip(age, fwd):
            recs.append((D.dates[d].year, '30-180天' if a < 180 else ('180-365天' if a < 365 else '1年以上'), f_))
    a = pd.DataFrame(recs, columns=['year', 'age', 'fwd'])
    g = a.groupby('age').fwd.agg(['count', 'mean', 'median']).reset_index()
    g['mean'] = g['mean'].map('{:+.1%}'.format)
    g['median'] = g['median'].map('{:+.1%}'.format)
    P('池内（流动性前 100）按"上线多久"分组，之后 30 天相对 BTC 的超额（每周取样）：')
    tbl(g.rename(columns={'age': '上线时间', 'count': '样本', 'mean': '平均', 'median': '中位'}))
    y = a.pivot_table(index='year', columns='age', values='fwd', aggfunc='median').reset_index()
    for c in y.columns[1:]:
        y[c] = y[c].map(lambda v: f'{v:+.1%}' if np.isfinite(v) else '-')
    P('分年（中位）：')
    tbl(y)


# ---------------------------------------------------------------- I4 crash dip buy
def idea4(D, d0, d1):
    P('## 思路 4：暴跌后买高弹性山寨（给被迫卖出的人提供流动性）')
    P('规则：BTC 单日收盘跌幅 ≥ 8% 的当天收盘，买流动性前 50 里"60 日对 BTC beta"最高的 10 个等权，持有 3 天卖出；触发后 7 天内不再触发。'
      '对照：同一时刻只买 BTC；同一时刻随机 10 个 ×1000。另报持有 1 / 7 / 14 天（不参与结论）。成本按来回扣。')
    rb = D.r[:, D.btc]
    ev = []
    d = d0
    while d <= d1 - 14:
        if rb[d] <= -0.08:
            ev.append(d)
            d += 7
        else:
            d += 1
    rng = np.random.default_rng(RNG)
    rows = []
    agg = {h: [] for h in (1, 3, 7, 14)}
    for d in ev:
        p = D.pool(d, 50)
        w = D.r[d - 59:d + 1]
        x = w[:, D.btc]
        beta = np.array([np.cov(w[:, i][np.isfinite(w[:, i])], x[np.isfinite(w[:, i])])[0, 1] / np.var(x[np.isfinite(w[:, i])], ddof=1)
                         if np.isfinite(w[:, i]).sum() >= 40 else -9 for i in p])
        pick = p[np.argsort(-beta, kind='stable')[:10]]
        cost = np.mean([2 * D.cost(i, d) for i in pick])
        row = {'日期': D.dates[d].date(), 'BTC 当日': f'{rb[d]:+.1%}'}
        for h in (1, 3, 7, 14):
            alt = np.mean(D.c[d + h, pick] / D.c[d, pick] - 1) - cost
            b = D.btcpx[d + h] / D.btcpx[d] - 1 - 0.003
            rd = np.array([np.mean(D.c[d + h, s] / D.c[d, s] - 1) for s in (rng.choice(p, 10, replace=False) for _ in range(NR))]) - cost
            agg[h].append((alt, b, np.median(rd), pct_rank(alt, rd)))
            if h == 3:
                row.update({'高 beta 3天': f'{alt:+.1%}', 'BTC 3天': f'{b:+.1%}', '随机中位 3天': f'{np.median(rd):+.1%}', '随机分位': f'{pct_rank(alt, rd):.0%}'})
            if h in (7, 14):
                row[f'高 beta {h}天'] = f'{alt:+.1%}'
        rows.append(row)
    tbl(pd.DataFrame(rows))
    s = []
    for h in (1, 3, 7, 14):
        a = np.array(agg[h])
        n = len(a)
        t = a[:, 0].mean() / (a[:, 0].std(ddof=1) / np.sqrt(n)) if n > 1 else np.nan
        s.append({'持有天数': h, '次数': n, '高 beta 平均': f'{a[:, 0].mean():+.1%}', '中位': f'{np.median(a[:, 0]):+.1%}', '赚钱次数': int((a[:, 0] > 0).sum()),
                  '最差一次': f'{a[:, 0].min():+.1%}', 'BTC 平均': f'{a[:, 1].mean():+.1%}', '随机平均': f'{a[:, 2].mean():+.1%}',
                  '比 BTC 多': f'{(a[:, 0] - a[:, 1]).mean():+.1%}', 't 值(对 0)': round(t, 2), '好于随机的次数': int((a[:, 3] > 0.5).sum())})
    tbl(pd.DataFrame(s))


# ---------------------------------------------------------------- I5 low-volume drop reversal (daily)
def idea5(D, d0, d1):
    P('## 思路 5：缩量急跌的反弹（日线版）')
    P('规则：流动性前 100 的币，当日跌幅超过自己过去 30 天日波动的 3 倍（另报 2 倍），当日成交额 < 过去 30 天中位数的 1.5 倍，且 BTC 当日跌幅不超过 1%；'
      '当日收盘买、持有 1 天（另报 3 天）。对照：放量急跌（成交额 ≥ 1.5 倍）、同一天池内全部币的平均。扣来回成本。按天聚类算置信区间。')
    r = D.r
    sd = pd.DataFrame(r).rolling(30).std().shift(1).values
    med = pd.DataFrame(D.v).rolling(30).median().shift(1).values
    recs = []
    for d in range(d0, d1 - 3):
        if not r[d, D.btc] > -0.01:
            continue
        p = D.pool(d, 100)
        z = r[d, p] / sd[d, p]
        base1 = np.nanmean(D.c[d + 1, p] / D.c[d, p] - 1)
        base3 = np.nanmean(D.c[d + 3, p] / D.c[d, p] - 1)
        for i, zz in zip(p, z):
            if np.isfinite(zz) and zz < -2 and med[d, i] > 0 and r[d, i] < -0.03:
                c = 2 * D.cost(i, d)
                recs.append((d, D.dates[d].year, zz < -3, D.v[d, i] < 1.5 * med[d, i], D.c[d + 1, i] / D.c[d, i] - 1 - c,
                             D.c[d + 3, i] / D.c[d, i] - 1 - c, base1, base3))
    df = pd.DataFrame(recs, columns=['d', 'year', 'z3', 'lowvol', 'r1', 'r3', 'b1', 'b3'])

    def line(name, g):
        if not len(g):
            return {'组': name, '次数': 0}
        row = {'组': name, '次数': len(g), '天数': g.d.nunique()}
        for h, col, b in ((1, 'r1', 'b1'), (3, 'r3', 'b3')):
            day = g.groupby('d')[col].mean()
            ex = (g[col] - g[b]).groupby(g.d).mean()
            row[f'{h}天 每笔净'] = f'{g[col].mean():+.2%}'
            row[f'{h}天 ±95%'] = f'±{1.96 * day.std(ddof=1) / np.sqrt(len(day)):.2%}'
            row[f'{h}天 比池内平均多'] = f'{ex.mean():+.2%} (t={ex.mean() / (ex.std(ddof=1) / np.sqrt(len(ex))):.1f})'
            row[f'{h}天 胜率'] = f'{(g[col] > 0).mean():.0%}'
        return row

    rows = [line('缩量急跌 3σ（主）', df[df.z3 & df.lowvol]), line('放量急跌 3σ（对照）', df[df.z3 & ~df.lowvol]),
            line('缩量急跌 2σ', df[df.lowvol]), line('放量急跌 2σ', df[~df.lowvol])]
    tbl(pd.DataFrame(rows))
    g = df[df.lowvol]
    y = g.groupby('year').agg(次数=('r1', 'size'), 一天每笔净=('r1', 'mean'), 三天每笔净=('r3', 'mean')).reset_index()
    y['一天每笔净'] = y['一天每笔净'].map('{:+.2%}'.format)
    y['三天每笔净'] = y['三天每笔净'].map('{:+.2%}'.format)
    P('缩量急跌 2σ 分年：')
    tbl(y)


# ---------------------------------------------------------------- I6 chain stablecoin flows
CHAINS = {'Ethereum': ['ETHUSDT'], 'Tron': ['TRXUSDT'], 'BSC': ['BNBUSDT'], 'Solana': ['SOLUSDT'], 'Arbitrum': ['ARBUSDT'],
          'Avalanche': ['AVAXUSDT'], 'Polygon': ['MATICUSDT', 'POLUSDT'], 'Optimism': ['OPUSDT'], 'Sui': ['SUIUSDT'],
          'Aptos': ['APTUSDT'], 'Near': ['NEARUSDT'], 'TON': ['TONUSDT']}


def idea6(D, d0, d1, datadir):
    P('## 思路 6：稳定币流进哪条链，就买哪条链的币')
    P('规则：每周日，用 7 天前的数据（防止数据回补）算各链稳定币供应的 28 天增速；增速 > 5% 的链里取最高的 3 条，各 1/3 买它的原生币持有 1 周；'
      '不够 3 条的份额拿 BTC。对照：一直拿 BTC；所有可买的链币等权；同一天在可买链币里随机选同样数量 ×1000。')
    st = json.load(gzip.open(datadir + '/stablecoins.json.gz', 'rt'))
    sup = {}
    for ch in CHAINS:
        if ch in st:
            s = pd.Series({pd.Timestamp(a, unit='D'): b for a, b in st[ch]}).reindex(D.dates).ffill()
            sup[ch] = s.values
    idx = {s: i for i, s in enumerate(D.syms)}

    def token(ch, d):
        for s in CHAINS[ch]:
            if s in idx and D.has[d, idx[s]] and d - D.first[idx[s]] >= 30:
                return idx[s]
        return None

    ss = sundays(D, d0, d1)
    counts, cands, picks = {}, {}, {}
    for d in ss:
        g = []
        av = []
        for ch in sup:
            t = token(ch, d)
            if t is None:
                continue
            av.append(t)
            a, b = sup[ch][d - 7], sup[ch][d - 35]
            if a > 0 and b > 0 and a / b - 1 > 0.05:
                g.append((a / b - 1, t))
        g.sort(reverse=True)
        picks[d] = [t for _, t in g[:3]]
        counts[d] = len(picks[d])
        cands[d] = np.array(av)

    def chooser(pk):
        def ch(d):
            if d not in pk:
                return None
            w = {int(i): 1 / 3 for i in pk[d]}
            w[D.btc] = w.get(D.btc, 0) + (3 - len(pk[d])) / 3
            return w

        return ch

    nav, turn = run_weights(D, d0, d1, chooser(picks))
    rng = np.random.default_rng(RNG)
    rnd = []
    for _ in range(NR):
        pk = {d: (rng.choice(cands[d], size=min(counts[d], len(cands[d])), replace=False) if counts[d] else []) for d in ss}
        rnd.append(run_weights(D, d0, d1, chooser(pk))[0])
    rnd = np.array(rnd)
    rows = [twin_cols(D, summary(D, '稳定币流入前 3 条链', nav, d0, turn), nav, rnd, d0)]
    eq, turn = run_weights(D, d0, d1, lambda d: ({int(i): 1 / len(cands[d]) for i in cands[d]} if d in cands and len(cands[d]) else None))
    rows.append(summary(D, '所有链币等权', eq, d0, turn))
    tbl(pd.DataFrame(rows))
    P(f'触发情况：{len(ss)} 个周日里，{sum(1 for d in ss if counts[d] > 0)} 个有至少 1 条链入选，平均入选 {np.mean(list(counts.values())):.1f} 条。')
    P('')


# ---------------------------------------------------------------- I7 weekday effect
def idea7(D, d0, d1):
    P('## 思路 7：星期几效应（山寨篮子相对 BTC）')
    P('规则：流动性前 20 山寨等权篮子的日收益减 BTC 日收益，按星期几（UTC 日）统计；只用 2020–2022 选出最好的一天，2023 年以后只在那一天拿山寨、其余拿 BTC，每周来回扣 0.3%。')
    ex = np.full(D.T, np.nan)
    for d in range(d0, d1 + 1):
        p = D.pool(d - 1, 20)
        ex[d] = np.nanmean(D.r[d, p]) - D.r[d, D.btc]
    s = pd.Series(ex, index=D.dates).dropna()
    names = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
    a, b = s[s.index <= IS_END], s[s.index > IS_END]
    rows = []
    for k in range(7):
        x, y = a[a.index.dayofweek == k], b[b.index.dayofweek == k]
        rows.append({'星期': names[k], '样本内 日均超额': f'{x.mean():+.3%}', '样本内 t': round(x.mean() / (x.std() / np.sqrt(len(x))), 2),
                     '样本外 日均超额': f'{y.mean():+.3%}', '样本外 t': round(y.mean() / (y.std() / np.sqrt(len(y))), 2)})
    tbl(pd.DataFrame(rows))
    best = int(np.argmax([a[a.index.dayofweek == k].mean() for k in range(7)]))
    y = b[b.index.dayofweek == best]
    net = y - 0.003
    P(f'样本内最好的是{names[best]}。样本外只在{names[best]}拿山寨：每周超额 {y.mean():+.3%}，扣来回 0.3% 后 {net.mean():+.3%}，'
      f'{len(y)} 周累计相对 BTC {np.prod(1 + net) - 1:+.1%}。')
    P('')


def idea9(D, d0, d1):
    P('## 参照：山寨相对 BTC 的"基础胜率"')
    P('流动性前 50 的每个币，买入后持有 90 / 365 天，相对 BTC 的超额（每 30 天取样一次）：')
    rows = []
    for h in (90, 365):
        recs = []
        for d in range(d0, d1 - h, 30):
            p = D.pool(d, 50)
            recs += [(D.dates[d].year, x) for x in (D.c[d + h, p] / D.c[d, p]) / (D.btcpx[d + h] / D.btcpx[d]) - 1]
        g = pd.DataFrame(recs, columns=['y', 'x'])
        row = {'持有': f'{h} 天', '样本': len(g), '跑赢 BTC 占比': f'{(g.x > 0).mean():.0%}', '中位超额': f'{g.x.median():+.0%}', '平均超额': f'{g.x.mean():+.0%}',
               '涨幅超 BTC 2 倍以上占比': f'{(g.x > 1).mean():.1%}'}
        for y, gg in g.groupby('y'):
            row[f'{y} 跑赢占比'] = f'{(gg.x > 0).mean():.0%}'
        rows.append(row)
    tbl(pd.DataFrame(rows))


# ---------------------------------------------------------------- I10 top gainers, daily proxy of V6 incl. 2020-2021
def idea10(D, d0, d1):
    P('## 思路 10：涨幅榜惯性的日线版（把 V6 的原理放到 2020–2021 大山寨季里检验）')
    P('规则：每天收盘，在流动性前 150（30 日成交额中位数 ≥ 300 万 U）里取当日涨幅前 3，收盘买入，持有 1 天（另报 3 天）卖出，扣来回成本。'
      '对照：同一天池内全部币的平均（= 随机买的期望）；镜像：当日跌幅前 3。这不是 V6 本身（V6 用 15 分钟数据和利润保护），只看"强者第二天是否继续强"。')
    recs = []
    for d in range(d0, d1 - 3):
        p = D.pool(d, 150)
        p = p[D.medv[d, p] >= 3e6]
        x = D.r[d, p]
        ok = np.isfinite(x)
        p, x = p[ok], x[ok]
        if len(p) < 20:
            continue
        o = np.argsort(x, kind='stable')
        f1 = D.c[d + 1, p] / D.c[d, p] - 1
        f3 = D.c[d + 3, p] / D.c[d, p] - 1
        for side, sel in (('涨幅前 3', o[-3:]), ('跌幅前 3', o[:3])):
            for j in sel:
                c = 2 * D.cost(p[j], d)
                recs.append((d, D.dates[d].year, side, x[j], f1[j] - c, f3[j] - c, f1.mean() - c, f3.mean() - c))
    df = pd.DataFrame(recs, columns=['d', 'year', 'side', 'r0', 'r1', 'r3', 'b1', 'b3'])
    rows = []
    for side in ('涨幅前 3', '跌幅前 3'):
        for y, g in list(df[df.side == side].groupby('year')) + [('样本内 2020-22', df[(df.side == side) & (df.year <= 2022)]),
                                                                  ('样本外 2023 起', df[(df.side == side) & (df.year >= 2023)]), ('全部', df[df.side == side])]:
            row = {'组': side, '年份': y, '笔数': len(g)}
            for h, col, b in ((1, 'r1', 'b1'), (3, 'r3', 'b3')):
                ex = (g[col] - g[b]).groupby(g.d).mean()
                day = g.groupby('d')[col].mean()
                row[f'{h}天 每笔净'] = f'{g[col].mean():+.2%}'
                row[f'{h}天 ±95%'] = f'±{1.96 * day.std(ddof=1) / np.sqrt(len(day)):.2%}'
                row[f'{h}天 比池内平均多'] = f'{ex.mean():+.2%} (t={ex.mean() / (ex.std(ddof=1) / np.sqrt(len(ex))):.1f})'
            row['1天 胜率'] = f'{(g.r1 > 0).mean():.0%}'
            row['1天 ≥+20% 占比'] = f'{(g.r1 >= 0.2).mean():.1%}'
            row['1天 ≤-20% 占比'] = f'{(g.r1 <= -0.2).mean():.1%}'
            rows.append(row)
    tbl(pd.DataFrame(rows))
    g = df[df.side == '涨幅前 3']
    day = g.groupby('d').r1.mean()
    nav = np.cumprod(1 + day.values)
    P(f'涨幅前 3：平均每笔盈利 {g.r1[g.r1 > 0].mean():+.1%}、平均每笔亏损 {g.r1[g.r1 <= 0].mean():+.1%}。')
    P('')


# ---------------------------------------------------------------- I11 how common are multi-baggers, and what happens after a coin has already jumped
def idea11(D, d0, d1):
    P('## 思路 11："几周翻几倍"的币到底有多少，启动后再追会怎样')
    P('（a）每月 1 日，流动性前 150 的每个币，看之后 60 天内的最高收盘价相对当天翻了多少倍：')
    recs = []
    for d in month_starts(D, d0, d1 - 60):
        p = D.pool(d, 150)
        mx = np.nanmax(D.c[d + 1:d + 61, p], axis=0) / D.c[d, p]
        end = D.c[d + 60, p] / D.c[d, p]
        recs += [(D.dates[d].year, a, b) for a, b in zip(mx, end)]
    g = pd.DataFrame(recs, columns=['year', 'mx', 'end'])
    rows = []
    for y, x in list(g.groupby('year')) + [('全部', g)]:
        rows.append({'年份': y, '样本(币×月)': len(x), '最高 ≥2 倍': f'{(x.mx >= 2).mean():.1%}', '≥3 倍': f'{(x.mx >= 3).mean():.1%}', '≥5 倍': f'{(x.mx >= 5).mean():.2%}',
                     '≥10 倍': f'{(x.mx >= 10).mean():.2%}', '60 天后还 ≥2 倍': f'{(x.end >= 2).mean():.1%}', '60 天后亏 30% 以上': f'{(x.end <= 0.7).mean():.0%}',
                     '60 天后中位': f'{x.end.median() - 1:+.0%}'})
    tbl(pd.DataFrame(rows))
    P('（b）已经启动的币（7 天涨 ≥ 50%，同一币 30 天内只算第一次；流动性前 150），当天收盘追进去，之后 30 天：')
    recs = []
    lastev = {}
    for d in range(d0, d1 - 30):
        p = D.pool(d, 150)
        r7 = D.c[d, p] / D.c[d - 7, p] - 1
        for i in p[np.isfinite(r7) & (r7 >= 0.5)]:
            if d - lastev.get(i, -99) < 30:
                continue
            lastev[i] = d
            path = D.c[d + 1:d + 31, i] / D.c[d, i]
            recs.append((D.dates[d].year, path[-1] - 1, np.nanmax(path) - 1, np.nanmin(path) - 1,
                         D.btcpx[d + 30] / D.btcpx[d] - 1, np.nanmean(D.c[d + 30, p] / D.c[d, p] - 1)))
    g = pd.DataFrame(recs, columns=['year', 'end', 'mx', 'mn', 'btc', 'pool'])
    rows = []
    for y, x in list(g.groupby('year')) + [('全部', g)]:
        rows.append({'年份': y, '次数': len(x), '30 天后 中位': f'{x.end.median():+.0%}', '平均': f'{x.end.mean():+.0%}', '同期池内平均': f'{x.pool.mean():+.0%}',
                     '同期 BTC': f'{x.btc.mean():+.0%}', '期间再涨 ≥100%': f'{(x.mx >= 1).mean():.0%}', '30 天后仍 ≥+100%': f'{(x.end >= 1).mean():.0%}',
                     '期间曾跌 ≥30%': f'{(x.mn <= -0.3).mean():.0%}', '30 天后亏 ≥30%': f'{(x.end <= -0.3).mean():.0%}'})
    tbl(pd.DataFrame(rows))


# ---------------------------------------------------------------- I12 does the alt/BTC ratio trend or revert
def idea12(D, d0, d1):
    P('## 思路 12：山寨/BTC 比值是延续还是回归（决定该"追山寨季"还是"在山寨最弱时埋伏"）')
    P('流动性前 40 山寨等权篮子（每日再平衡、不扣费，只作指标）除以 BTC = R。每 30 天看一次：过去 90 天 R 的涨跌，对之后 90 天 R 的涨跌。')
    R = np.ones(D.T)
    for d in range(d0 - 400, D.T):
        p = D.pool(d - 1, 40)
        x = np.nanmean(D.r[d, p]) if len(p) >= 20 else np.nan
        R[d] = R[d - 1] * (1 + x) / (1 + D.r[d, D.btc]) if np.isfinite(x) and np.isfinite(D.r[d, D.btc]) else R[d - 1]
    recs = []
    for d in range(d0, d1 - 90, 30):
        recs.append((D.dates[d], R[d] / R[d - 90] - 1, R[d + 90] / R[d] - 1, R[d] / R[d - 365:d + 1].max() - 1))
    g = pd.DataFrame(recs, columns=['date', 'past', 'fwd', 'dd'])
    rows = []
    for name, m in [('过去 90 天 R 涨 > 20%', g.past > 0.2), ('涨 0～20%', (g.past > 0) & (g.past <= 0.2)), ('跌 0～20%', (g.past <= 0) & (g.past > -0.2)),
                    ('跌 20～40%', (g.past <= -0.2) & (g.past > -0.4)), ('跌 > 40%', g.past <= -0.4), ('R 距一年高点跌 > 60%', g.dd <= -0.6), ('全部', g.past == g.past)]:
        x = g[m]
        rows.append({'当时状态': name, '次数': len(x), '之后 90 天 R 中位': f'{x.fwd.median():+.0%}' if len(x) else '-', '平均': f'{x.fwd.mean():+.0%}' if len(x) else '-',
                     'R 上涨的占比': f'{(x.fwd > 0).mean():.0%}' if len(x) else '-', '涉及年份': ','.join(sorted({str(t.year) for t in x.date}))})
    tbl(pd.DataFrame(rows))
    P(f'相关系数（过去 90 天 vs 之后 90 天，取样有重叠、只作参考）：{np.corrcoef(g.past, g.fwd)[0, 1]:+.2f}；'
      f'R 全期从 1 变成 {R[d1] / R[d0]:.3f}（{D.dates[d0].date()} ～ {D.dates[d1].date()}）。')
    P('')



def main():
    datadir, outfile = sys.argv[1], sys.argv[2]
    only = set(sys.argv[3].split(',')) if len(sys.argv) > 3 else None
    D = Data(datadir)
    d0, d1 = D.day(START), D.T - 1
    P(f'# 新思路回测结果（idea_study.py 自动生成）\n\n数据：Binance 现货日线 {D.dates[0].date()} ～ {D.dates[-1].date()}，USDT 交易对 {D.n} 个（含已下架），'
      f'其中山寨 {int(D.alt.sum())} 个。评估 {START.date()} ～ {D.dates[-1].date()}；样本内到 {IS_END.date()}，之后是样本外。随机对照每组 {NR} 个。\n')
    run = lambda k: only is None or k in only
    if run('9'): idea9(D, d0, d1)
    if run('1'): idea1(D, d0, d1)
    if run('2'): idea2(D, d0, d1)
    if run('3'): idea3(D)
    if run('4'): idea4(D, d0, d1)
    if run('5'): idea5(D, d0, d1)
    if run('6'): idea6(D, d0, d1, datadir)
    if run('7'): idea7(D, d0, d1)
    if run('8'): idea8(D, d0, d1)
    if run('10'): idea10(D, d0, d1)
    if run('11'): idea11(D, d0, d1)
    if run('12'): idea12(D, d0, d1)
    open(outfile, 'w', encoding='utf-8').write('\n'.join(out_lines) + '\n')


main()
