"""BTC 现货趋势跟踪（4 条均线 × 4 份）——参考实现（golden reference）。

规则：
- 本金分 4 份（默认 $20,000 → 每份 $5,000），分别绑定 SMA75 / SMA100 / SMA125 / SMA150（日线收盘价简单均线）。
- 每天 UTC 日线收盘后，用"已收盘"的那根日线判断（信号日 = D），在 D+1 开盘执行：
    该份为 USDT 且 close_D > SMA_D × 1.02  → 买入（该份全部 USDT 买 BTC）
    该份为 BTC  且 close_D < SMA_D × 0.98  → 卖出（该份全部 BTC 卖成 USDT）
    其他情况不动。
- 手续费 0.1%/笔；每份独立记账，盈亏各自滚动。
- 启动当天：按启动前最后一根已收盘日线判断，满足买入条件的份直接买入。

用法：
  python3 trend_ref.py btc_long.csv 2018-08-01            # 回测并输出 trades_golden.csv
  python3 trend_ref.py btc_long.csv --signal              # 只输出最新一根日线的信号（影子盘用）
"""
import csv, sys

MAS = (75, 100, 125, 150)
BAND = 0.02
FEE = 0.001


def load(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            rows.append(dict(date=r["date"], open=float(r["open"]), high=float(r["high"]),
                             low=float(r["low"]), close=float(r["close"])))
    return rows


def sma_at(rows, i, n):
    """SMA of closes over rows[i-n+1 .. i] (inclusive). None if not enough history."""
    if i - n + 1 < 0:
        return None
    return sum(r["close"] for r in rows[i - n + 1:i + 1]) / n


def signal(rows, i, holding):
    """Signal from the CLOSED bar i for one slot. holding: True if slot holds BTC."""
    out = []
    for k, n in enumerate(MAS):
        m = sma_at(rows, i, n)
        c = rows[i]["close"]
        if m is None:
            out.append("HOLD")
        elif not holding[k] and c > m * (1 + BAND):
            out.append("BUY")
        elif holding[k] and c < m * (1 - BAND):
            out.append("SELL")
        else:
            out.append("HOLD")
    return out


def backtest(rows, start_date, capital=20000.0):
    i0 = next(i for i, r in enumerate(rows) if r["date"] >= start_date)
    per = capital / len(MAS)
    usd = [per] * len(MAS)
    btc = [0.0] * len(MAS)
    holding = [False] * len(MAS)
    trades = []
    peak = 0.0
    mdd = 0.0
    for i in range(i0, len(rows)):
        # execute signals from bar i-1 at the open of bar i
        sig = signal(rows, i - 1, holding)
        px = rows[i]["open"]
        for k, s in enumerate(sig):
            if s == "BUY":
                btc[k] = usd[k] / px * (1 - FEE); trades.append((rows[i]["date"], MAS[k], "BUY", px, usd[k], btc[k]))
                usd[k] = 0.0; holding[k] = True
            elif s == "SELL":
                usd[k] = btc[k] * px * (1 - FEE); trades.append((rows[i]["date"], MAS[k], "SELL", px, usd[k], btc[k]))
                btc[k] = 0.0; holding[k] = False
        v = sum(btc) * rows[i]["close"] + sum(usd)
        peak = max(peak, v); mdd = min(mdd, v / peak - 1)
    final = sum(btc) * rows[-1]["close"] + sum(usd)
    return trades, final, mdd, holding


if __name__ == "__main__":
    rows = load(sys.argv[1])
    if len(sys.argv) > 2 and sys.argv[2] == "--signal":
        i = len(rows) - 1
        print("最新已收盘日线:", rows[i]["date"], "收盘", rows[i]["close"])
        for n in MAS:
            m = sma_at(rows, i, n)
            print(f"SMA{n}: {m:,.2f}  买入线 {m*(1+BAND):,.2f}  卖出线 {m*(1-BAND):,.2f}")
        sys.exit(0)
    start = sys.argv[2] if len(sys.argv) > 2 else "2018-08-01"
    trades, final, mdd, holding = backtest(rows, start)
    with open("trades_golden.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["exec_date", "sma", "side", "price", "usdt_after_or_spent", "btc"])
        for t in trades:
            w.writerow([t[0], t[1], t[2], f"{t[3]:.2f}", f"{t[4]:.2f}", f"{t[5]:.8f}"])
    print(f"start {start} trades {len(trades)} final ${final:,.2f} ({final/20000-1:+.1%}) mdd {mdd:.1%} holding {holding}")
