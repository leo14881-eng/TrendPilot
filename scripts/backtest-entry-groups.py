"""V2 买入端分组统计：现行 vs 随机对照（同一特征分组内），校准期/验证期分开。
用法：python3 scripts/backtest-entry-groups.py 结果目录 > groups.md（结果目录 = EntryStudy explore 的输出，含 entries-*.csv）
"""
import sys, glob, os, math
import pandas as pd, numpy as np

d = sys.argv[1]
SPLIT = pd.Timestamp("2025-06-15", tz="UTC")
FROM = pd.Timestamp("2022-10-01", tz="UTC")


def load(path):
    x = pd.read_csv(path)
    x["entry"] = pd.to_datetime(x["entry"], utc=True)
    x = x[x.entry >= FROM].copy()
    x["period"] = np.where(x.entry < SPLIT, "cal", "val")
    x["hits"] = x["hits"].fillna("")
    return x


live = load(os.path.join(d, "entries-现行.csv"))
rnd = pd.concat([load(p) for p in sorted(glob.glob(os.path.join(d, "entries-随机*.csv")))], ignore_index=True)
nseeds = len(glob.glob(os.path.join(d, "entries-随机*.csv")))


def stats(v):
    v = np.asarray(v, float)
    n = len(v)
    if n == 0:
        return dict(n=0, win=np.nan, aw=np.nan, al=np.nan, mean=np.nan, se=np.nan, sum=0.0)
    w = v[v > 0]; l = v[v < 0]
    return dict(n=n, win=len(w) / n, aw=w.mean() if len(w) else np.nan, al=l.mean() if len(l) else np.nan,
                mean=v.mean(), se=v.std(ddof=1) / math.sqrt(n) if n > 1 else np.nan, sum=v.sum())


# 分组定义：数值特征按现行校准期的五分位切；类别特征原样
NUM = ["score", "rel1h", "rel4h", "accel", "volAccel", "compression", "ret1h", "ret4h", "ret24h", "fromHigh24h",
       "fromHigh7d", "quoteVol24h", "volRank", "trendShare", "breadth24h", "above20d", "btc1h", "btc24h", "alt24h",
       "ageDays", "atr1d"]
CAT = ["riskRegime", "regime6", "utcHour4", "weekday", "nhits"]
for x in (live, rnd):
    x["utcHour4"] = (x.utcHour // 4) * 4
    x["nhits"] = x.hits.apply(lambda h: len([t for t in h.split("+") if t]))
    for t in ["REL_1H", "REL_4H", "ACCEL", "VOLUME", "COMPRESSION", "MOMENTUM"]:
        x["has_" + t] = x.hits.apply(lambda h: t in h.split("+")).astype(int)
CAT += ["has_COMPRESSION", "has_MOMENTUM", "has_VOLUME", "has_ACCEL", "has_REL_1H", "has_REL_4H"]

cal = live[live.period == "cal"]
edges = {}
for f in NUM:
    qs = cal[f].dropna().quantile([0, .2, .4, .6, .8, 1]).values
    qs = np.unique(qs)
    qs[0] = -np.inf; qs[-1] = np.inf
    edges[f] = qs


def bucket(x, f):
    if f in edges:
        return pd.cut(x[f], edges[f], include_lowest=True, duplicates="drop").astype(str)
    return x[f].astype(str)


rows = []
for f in NUM + CAT:
    lb = bucket(live, f); rb = bucket(rnd, f)
    for b in sorted(set(lb.dropna())):
        if b == "nan":
            continue
        r = dict(feature=f, bucket=b)
        for per in ("cal", "val"):
            L = live[(lb == b) & (live.period == per)].pnl
            R = rnd[(rb == b) & (rnd.period == per)].pnl
            s = stats(L); t = stats(R)
            diff = s["mean"] - t["mean"]
            se = math.sqrt((s["se"] if s["n"] > 1 else 0) ** 2 + (t["se"] if t["n"] > 1 else 0) ** 2) if s["n"] > 1 and t["n"] > 1 else np.nan
            r.update({f"{per}_n": s["n"], f"{per}_win": s["win"], f"{per}_aw": s["aw"], f"{per}_al": s["al"],
                      f"{per}_mean": s["mean"], f"{per}_se": s["se"], f"{per}_sum": s["sum"], f"{per}_rmean": t["mean"],
                      f"{per}_rn": t["n"] / max(nseeds, 1), f"{per}_diff": diff, f"{per}_dse": se})
        rows.append(r)
G = pd.DataFrame(rows)
G.to_csv(os.path.join(d, "groups.csv"), index=False)

# 总体
print(f"随机对照种子数 {nseeds}\n")
print("| 期间 | 现行笔数 | 胜率 | 平均盈 | 平均亏 | 每笔期望 ±95% | 随机每笔期望 ±95% | 差值 ±95% |")
print("|---|---:|---:|---:|---:|---:|---:|---:|")
for per in ("cal", "val", "all"):
    L = live if per == "all" else live[live.period == per]
    R = rnd if per == "all" else rnd[rnd.period == per]
    s, t = stats(L.pnl), stats(R.pnl)
    dse = math.sqrt(s["se"] ** 2 + t["se"] ** 2)
    print(f"| {per} | {s['n']} | {s['win']:.0%} | {s['aw']:.2f} | {s['al']:.2f} | {s['mean']:.2f} ±{1.96*s['se']:.2f} | "
          f"{t['mean']:.2f} ±{1.96*t['se']:.2f} | {s['mean']-t['mean']:+.2f} ±{1.96*dse:.2f} |")

ntests = len(G)
zb = abs(__import__("statistics").NormalDist().inv_cdf(0.025 / ntests))
print(f"\n分组数 {ntests}，Bonferroni 校正后 z 阈值 {zb:.2f}（校准期筛选），验证期只要求同号且 95% 显著\n")


def show(df, title):
    print(f"\n### {title}\n")
    print("| 特征 | 分组 | 校准 笔数 | 校准 每笔 | 校准 随机 | 校准 差值±95% | 验证 笔数 | 验证 胜率 | 验证 平均盈/亏 | 验证 每笔 | 验证 随机 | 验证 差值±95% | 验证 合计 |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for _, r in df.iterrows():
        print(f"| {r.feature} | {r.bucket} | {r.cal_n} | {r.cal_mean:.2f} | {r.cal_rmean:.2f} | {r.cal_diff:+.2f}±{1.96*r.cal_dse:.2f} | "
              f"{r.val_n} | {r.val_win:.0%} | {r.val_aw:.1f}/{r.val_al:.1f} | {r.val_mean:.2f} | {r.val_rmean:.2f} | "
              f"{r.val_diff:+.2f}±{1.96*r.val_dse:.2f} | {r.val_sum:,.0f} |")


G["cal_z"] = G.cal_mean / G.cal_se
G["val_z"] = G.val_mean / G.val_se
G["cal_dz"] = G.cal_diff / G.cal_dse
G["val_dz"] = G.val_diff / G.val_dse
ok = G[(G.cal_n >= 100) & (G.val_n >= 50)]
show(ok.sort_values("cal_mean").head(15), "校准期每笔最亏的 15 组")
show(ok.sort_values("cal_mean", ascending=False).head(15), "校准期每笔最赚的 15 组")
sig_loss = ok[(ok.cal_z <= -zb) & (ok.val_mean < 0) & (ok.val_z <= -1.96)]
sig_win = ok[(ok.cal_z >= zb) & (ok.val_mean > 0) & (ok.val_z >= 1.96)]
show(sig_loss, "显著亏钱（校准期 Bonferroni 显著 <0，验证期同号 95% 显著）")
show(sig_win, "显著赚钱（校准期 Bonferroni 显著 >0，验证期同号 95% 显著）")
sel_bad = ok[(ok.cal_dz <= -zb) & (ok.val_dz <= -1.96)]
sel_good = ok[(ok.cal_dz >= zb) & (ok.val_dz >= 1.96)]
show(sel_bad, "显著差于同组随机（选币拖后腿）")
show(sel_good, "显著好于同组随机（选币有本事）")
print("\n### 全部分组（按特征）\n")
show(G, "全部")
