"""独立核对（用法：python3 scripts/backtest-verify.py <回测结果目录>）：只读回测导出的明细，不用回测代码，重算报告里的关键数字。"""
import csv, gzip, math, sys
from collections import defaultdict
D = sys.argv[1]

def rows(path):
    op = gzip.open if path.endswith('.gz') else open
    with op(path, 'rt', encoding='utf-8') as f:
        lines = [l for l in f if not l.startswith('#')]
    return list(csv.DictReader(lines))

def cluster(xs, days):
    n = len(xs); m = sum(xs) / n
    r = defaultdict(float)
    for x, d in zip(xs, days): r[d] += x - m
    D_ = len(r); se = math.sqrt(sum(e*e for e in r.values()) * D_ / (D_ - 1)) / n
    daymeans = defaultdict(list)
    for x, d in zip(xs, days): daymeans[d].append(x)
    dm = sum(sum(v)/len(v) for v in daymeans.values()) / len(daymeans)
    return m, m - 1.96*se, m + 1.96*se, dm

print("== 埋伏：每种迹象 × 卖法（按笔平均 / 95%区间 / 按天平均 / 不算成本）==")
t = rows(f"{D}/prepump-trades.csv.gz")
by = defaultdict(list)
for r in t: by[r['sign']].append(r)
for k in sorted(by, key=int):
    rs = by[k]; days = [r['entry_time_utc'][:10] for r in rs]
    p10 = sum(float(r['max_up_24h_pct']) >= 10 for r in rs) / len(rs) * 100
    p20 = sum(float(r['max_up_24h_pct']) >= 20 for r in rs) / len(rs) * 100
    print(f"迹象{k} 笔数{len(rs)} +10%:{p10:.1f}% +20%:{p20:.1f}%")
    for j in range(5):
        m, lo, hi, dm = cluster([float(r[f'net_{j}']) for r in rs], days)
        g = sum(float(r[f'gross_{j}']) for r in rs) / len(rs)
        print(f"   卖法{j}: 每笔 {m:.3f} 区间 {lo:.3f}~{hi:.3f} 按天平均 {dm:.3f} 不算成本 {g:.3f}")

e = rows(f"{D}/prepump-events.csv.gz")
cols = [c for c in e[0] if c.startswith('sign_')]
anyc = sum(any(r[c] == '1' for c in cols) for r in e)
print(f"\n== 大涨事件 {len(e)} 次；任意迹象合并抓到 {anyc} 次 = {anyc/len(e)*100:.1f}%")
for c in cols: print(f"   {c}: {sum(r[c]=='1' for r in e)/len(e)*100:.1f}%")

print("\n== 追涨：每种触发 × 卖法（每笔净 / 区间 / 不算成本 / 低成本）==")
p = rows(f"{D}/pump-trades.csv.gz")
by = defaultdict(list)
for r in p: by[r['trigger']].append(r)
for k in sorted(by, key=int):
    rs = by[k]; days = [r['entry_time_utc'][:10] for r in rs]
    print(f"触发{k} 笔数{len(rs)}")
    for j in range(7):
        m, lo, hi, dm = cluster([float(r[f'net_{j}']) for r in rs], days)
        g = sum(float(r[f'gross_{j}']) for r in rs) / len(rs); lw = sum(float(r[f'low_{j}']) for r in rs) / len(rs)
        print(f"   卖法{j}: 每笔 {m:.3f} 区间 {lo:.3f}~{hi:.3f} 不算成本 {g:.3f} 低成本 {lw:.3f}")

print("\n== BTC 50 日均线：只用收盘价独立重算 ==")
b = rows(f"{D}/trend-btc-daily.csv")
close = [float(r['btc_close']) for r in b]
def sim(ma_days):
    # 均线用"含今天"的过去 ma_days 个每日收盘；前面不够的天数用 CSV 中回测给出的均线校对
    nav = 1.0; held = False; navs = []; switches = 0
    for i in range(len(b)):
        if ma_days == 0: want = True
        else:
            if i + 1 >= ma_days: ma = sum(close[i+1-ma_days:i+1]) / ma_days
            else: ma = float(b[i][f'ma{ma_days}'])
            want = close[i] > ma
        r = 0.0
        if want != held: r -= 0.0015; switches += 1; held = want
        if held and i + 1 < len(b): r += close[i+1] / close[i] - 1
        nav *= 1 + r; navs.append(nav)
    peak = 1; dd = 0
    for v in navs: peak = max(peak, v); dd = max(dd, 1 - v/peak)
    return nav, dd, switches, navs
for ma, col in [(0, 'hold'), (50, 'ma50')]:
    nav, dd, sw, navs = sim(ma)
    diff = max(abs(a - float(r[f'nav_{col}'])) / float(r[f'nav_{col}']) for a, r in zip(navs, b))
    print(f"{col}: 收益 {(nav-1)*100:.1f}% 最大回撤 {dd*100:.1f}% 换仓 {sw} 次；与回测逐日净值最大相对差 {diff*100:.3f}%")
print(f"   区间 {b[0]['date_utc']} 收盘 {close[0]} → {b[-1]['date_utc']} 收盘 {close[-1]}")

print("\n== 前 150 等权池：按每期净收益连乘 ==")
for h in (24, 72, 168, 336):
    pr = rows(f"{D}/pool-150-{h}h.csv")
    nav = 1; peak = 1; dd = 0; gnav = 1
    for r in pr:
        nav *= 1 + float(r['net_pct'])/100; gnav *= 1 + float(r['gross_pct'])/100
        peak = max(peak, nav); dd = max(dd, 1 - nav/peak)
    cost = sum(float(r['turnover_cost_pct']) for r in pr)
    newc = sum(int(r['new_coins']) for r in pr) / len(pr)
    print(f"{h}h 调仓: {len(pr)} 期，扣成本累计 {(nav-1)*100:.1f}% 不扣成本 {(gnav-1)*100:.1f}% 回撤 {dd*100:.1f}% 换手成本合计 {cost:.1f}% 每期平均新进池 {newc:.1f} 个")

# ---- 补充核对：BTC 逐日对账、合并抓到率的对照 ----
import bisect
from datetime import datetime
def rows(p):
    op = gzip.open if p.endswith('.gz') else open
    with op(p,'rt',encoding='utf-8') as f: return list(csv.DictReader([l for l in f if not l.startswith('#')]))
b=rows(f"{D}/trend-btc-daily.csv"); close=[float(r['btc_close']) for r in b]
# 逐日对账，排除最后一行（回测最后一行含到下一天的收益）
nav=1;held=False;mx=0
for i in range(len(b)-1):
    ma=sum(close[i-49:i+1])/50 if i>=49 else float(b[i]['ma50'])
    want=close[i]>ma; r=0
    if want!=held: r-=0.0015; held=want
    if held: r+=close[i+1]/close[i]-1
    nav*=1+r; mx=max(mx,abs(nav/float(b[i]['nav_ma50'])-1))
    if (b[i]['in_ma50']=='1')!=want: print('持有状态不一致',b[i]['date_utc'])
print('ma50 除最后一行外逐日最大相对差 %.6f%%' % (mx*100))
print('回测末值 hold %.1f%% ma50 %.1f%% ; 末行 nav 对应 10-14 收盘约 %.0f' % ((float(b[-1]['nav_hold'])-1)*100,(float(b[-1]['nav_ma50'])-1)*100, close[-1]*float(b[-1]['nav_hold'])/float(b[-2]['nav_hold'])))
# 区间是否跨 0
t=rows(f"{D}/prepump-trades.csv.gz")
# 合并抓到率的对照：随便买的币-天里，事前24h有任意迹象的比例
hits=defaultdict(list)
for r in t:
    if r['sign']!='0': hits[r['symbol']].append(datetime.fromisoformat(r['entry_time_utc'].replace('Z','+00:00')).timestamp())
for v in hits.values(): v.sort()
n=0;c=0
for r in t:
    if r['sign']!='0': continue
    ts=datetime.fromisoformat(r['entry_time_utc'].replace('Z','+00:00')).timestamp()
    v=hits.get(r['symbol'],[])
    i=bisect.bisect_left(v,ts)
    # 迹象买入时间 = 迹象出现后下一根；事前 24h 内（含 24h）
    n+=1
    if i>0 and ts-v[i-1]<=86400+1: c+=1
print('随便买的币-天里事前24h出现过任意迹象：%.1f%% (%d/%d)'%(c/n*100,c,n))
