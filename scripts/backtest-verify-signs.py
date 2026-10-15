"""独立核对"迹象"类检验（大涨前埋伏、合约迹象）：只读导出的明细，不用回测代码。
用法：python3 scripts/backtest-verify-signs.py <结果目录> <前缀：prepump 或 deriv>
输出每种迹象 × 卖法的笔数、按笔平均、95% 区间（按天聚类）、不算成本、+10%/+20% 命中率，
合并抓到率，以及对照组（第一行）里事前出现过任意迹象的比例。"""
import bisect, csv, gzip, math, sys
from collections import defaultdict
from datetime import datetime

D, P = sys.argv[1], sys.argv[2]

def rows(path):
    with gzip.open(path, 'rt', encoding='utf-8') as f:
        head = [l for l in f]
    names = [l for l in head if l.startswith('# 迹象编号')]
    return names, list(csv.DictReader([l for l in head if not l.startswith('#')]))

def cluster(xs, days):
    n = len(xs); m = sum(xs) / n
    r = defaultdict(float)
    for x, d in zip(xs, days): r[d] += x - m
    k = len(r)
    if k < 2: return m, float('nan'), float('nan')
    se = math.sqrt(sum(e * e for e in r.values()) * k / (k - 1)) / n
    return m, m - 1.96 * se, m + 1.96 * se

names, t = rows(f"{D}/{P}-trades.csv.gz")
if names: print(names[0].strip())
exits = sorted({int(c.split('_')[1]) for c in t[0] if c.startswith('net_')})
by = defaultdict(list)
for r in t: by[int(r['sign'])].append(r)
for k in sorted(by):
    rs = by[k]; days = [r['entry_time_utc'][:10] for r in rs]
    p10 = sum(float(r['max_up_24h_pct']) >= 10 for r in rs) / len(rs) * 100
    p20 = sum(float(r['max_up_24h_pct']) >= 20 for r in rs) / len(rs) * 100
    print(f"迹象{k} 笔数 {len(rs)} 天数 {len(set(days))} +10%: {p10:.1f}% +20%: {p20:.1f}%")
    for j in exits:
        m, lo, hi = cluster([float(r[f'net_{j}']) for r in rs], days)
        g = sum(float(r[f'gross_{j}']) for r in rs) / len(rs)
        print(f"   卖法{j}: 每笔 {m:.3f} 区间 {lo:.3f}~{hi:.3f} 不算成本 {g:.3f}")

_, e = rows(f"{D}/{P}-events.csv.gz")
cols = [c for c in e[0] if c.startswith('sign_')]
anyc = sum(any(r[c] == '1' for c in cols) for r in e)
print(f"大涨事件 {len(e)} 次；任意迹象合并抓到 {anyc} 次 = {anyc / len(e) * 100:.1f}%")
for c in cols: print(f"   {c}: {sum(r[c] == '1' for r in e) / len(e) * 100:.1f}%")

sign_ids = {int(c.split('_')[1]) for c in cols}
ts = lambda s: datetime.fromisoformat(s.replace('Z', '+00:00')).timestamp()
hits = defaultdict(list)
for r in t:
    if int(r['sign']) in sign_ids: hits[r['symbol']].append(ts(r['entry_time_utc']))
for v in hits.values(): v.sort()
n = c = 0
for r in by.get(0, []):
    x = ts(r['entry_time_utc']); v = hits.get(r['symbol'], [])
    i = bisect.bisect_left(v, x); n += 1
    if i > 0 and x - v[i - 1] <= 86400 + 1: c += 1
if n: print(f"对照组的币-天里事前 24h 出现过任意迹象：{c / n * 100:.1f}% ({c}/{n})")
