"""独立核对候选 A：只读 tsmom-daily.csv，按日收益重算各段收益、回撤、夏普、平均仓位和每周超额区间。
用法：python3 scripts/backtest-verify-tsmom.py <结果目录> <校准期开始> <验证期开始>"""
import csv, math, sys
D, A, B = sys.argv[1:4]
with open(f"{D}/tsmom-daily.csv", encoding='utf-8') as f:
    lines = f.readlines()
names = lines[0].strip()
rows = list(csv.DictReader(lines[1:]))
idx = {r['date']: i for i, r in enumerate(rows)}
def seg(col, a, z, ecol=None):
    nav = pk = 1; dd = 0; s = s2 = 0; n = 0; e = 0
    for i in range(a, z + 1):
        x = float(rows[i][col]); nav *= 1 + x; pk = max(pk, nav); dd = max(dd, 1 - nav / pk)
        s += x; s2 += x * x; n += 1
        if ecol: e += float(rows[i][ecol])
    m = s / n; sd = math.sqrt(max(0, s2 / n - m * m))
    return (nav - 1) * 100, dd * 100, m / sd * math.sqrt(365) if sd else 0, e / n * 100
def weekly(c1, c2, a, z):
    w = []; acc = 0; k = 0
    for i in range(a, z + 1):
        acc += float(rows[i][c1]) - float(rows[i][c2]); k += 1
        if k == 7: w.append(acc * 100); acc = 0; k = 0
    m = sum(w) / len(w); se = math.sqrt(sum((x - m) ** 2 for x in w) / (len(w) - 1) / len(w))
    return m, m - 1.96 * se, m + 1.96 * se
print(names)
segs = [(idx[A], idx[B] - 1), (idx[B], len(rows) - 1)]
k = 0
while f'ret_{k}' in rows[0]:
    for a, z in segs:
        r = seg(f'ret_{k}', a, z, f'expo_{k}'); w = weekly(f'ret_{k}', f'matched_{k}', a, z)
        print(f"{k} {rows[a]['date']}: 收益 {r[0]:.0f}% 回撤 {r[1]:.1f}% 夏普 {r[2]:.2f} 仓位 {r[3]:.0f}% 每周超额 {w[0]:.3f} ({w[1]:.3f}~{w[2]:.3f})")
    k += 1
for a, z in segs:
    print('pool', seg('pool_ret', a, z)[:3], 'btc', seg('btc_ret', a, z)[:3])
