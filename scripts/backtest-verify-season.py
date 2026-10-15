"""独立核对山寨季轮动：只读 season-daily.csv，重算三种山寨季判断（不含调仓本身）、BTC 持有净值和各段收益/回撤。
用法：python3 scripts/backtest-verify-season.py <结果目录> <起算日 YYYY-MM-DD>"""
import csv, math, sys
D, START = sys.argv[1], sys.argv[2]
with open(f"{D}/season-daily.csv", encoding='utf-8') as f:
    lines = f.readlines()
print(lines[0].strip())
rows = list(csv.DictReader(lines[1:]))
f = lambda r, k: float(r[k]) if r[k] not in ('', 'NaN') else float('nan')
alt = [f(r, 'alt_index') for r in rows]; ab = [f(r, 'alt_btc') for r in rows]
br = [f(r, 'breadth') for r in rows]; asi = [f(r, 'altseason_index') for r in rows]
def sma(a, d, n):
    if d - n + 1 < 0: return float('nan')
    w = a[d - n + 1:d + 1]
    return float('nan') if any(math.isnan(x) for x in w) else sum(w) / n
bad = [0, 0, 0]; on2 = on3 = False
for d, r in enumerate(rows):
    r1 = (not math.isnan(sma(ab, d, 50))) and (not math.isnan(sma(alt, d, 50))) and ab[d] > sma(ab, d, 50) and alt[d] > sma(alt, d, 50)
    if not math.isnan(br[d]):
        on2 = True if br[d] >= 0.6 else (False if br[d] <= 0.4 else on2)
    if not math.isnan(asi[d]):
        on3 = True if asi[d] >= 0.75 else (False if asi[d] < 0.5 else on3)
    for k, v in enumerate((r1, on2, on3)):
        if int(r[f'r{k + 1}']) != int(v): bad[k] += 1
print(f"三种判断逐日重算，不一致天数：{bad}")
s = next(i for i, r in enumerate(rows) if r['date'] == START)
btc = [f(r, 'btc_close') for r in rows]
nav_btc = [f(r, 'nav_0') for r in rows]
diff = max(abs(nav_btc[d] / nav_btc[s] - btc[d] / btc[s]) / (btc[d] / btc[s]) for d in range(s, len(rows)))
print(f"BTC 持有净值和收盘价之比的最大相对差：{diff * 100:.4f}%（只差开仓成本）")
cols = [k for k in rows[0] if k.startswith('nav_')]
cuts = [START, '2020-01-01', '2022-01-01', '2024-01-01', rows[-1]['date']]
idx = {r['date']: i for i, r in enumerate(rows)}
def st(c, a, b):
    v = [f(rows[i], c) for i in range(idx[a], idx[b] + 1)]
    v = [x for x in v if not math.isnan(x)]
    pk = v[0]; dd = 0
    for x in v: pk = max(pk, x); dd = max(dd, 1 - x / pk)
    return (v[-1] / v[0] - 1) * 100, dd * 100
for c in cols:
    out = [st(c, cuts[0], cuts[-1])] + [st(c, cuts[k], cuts[k + 1]) for k in range(4)]
    print(c, ' | '.join(f"{a:.0f}/{b:.1f}" for a, b in out))
