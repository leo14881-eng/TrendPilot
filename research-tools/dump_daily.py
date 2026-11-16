#!/usr/bin/env python3
"""Research only: collect Binance spot daily bars (all USDT pairs, delisted included) from data.binance.vision
into one compact csv.gz, plus DefiLlama per-chain stablecoin supply. No API keys, public files only."""
import csv, gzip, io, json, sys, time, urllib.request, zipfile
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

LIST = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision"
BASE = "https://data.binance.vision/"
NS = "{http://s3.amazonaws.com/doc/2006-03-01/}"
FROM = sys.argv[2] if len(sys.argv) > 2 else "2019-01"


def get(url, tries=5):
    for k in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return r.read()
        except Exception as e:  # retry with backoff; a missing file just means no data for that month
            if k == tries - 1:
                print("give up", url, e, flush=True)
                return None
            time.sleep(2 * (k + 1))


def listing(prefix, delimiter=True):
    out, marker = [], ""
    while True:
        url = f"{LIST}?prefix={prefix}" + ("&delimiter=/" if delimiter else "") + (f"&marker={marker}" if marker else "")
        body = get(url)
        if body is None:
            return out
        root = ET.fromstring(body)
        keys = [e.find(NS + "Prefix").text for e in root.findall(NS + "CommonPrefixes")] if delimiter else \
            [e.find(NS + "Key").text for e in root.findall(NS + "Contents")]
        out += keys
        if root.find(NS + "IsTruncated").text != "true":
            return out
        nm = root.find(NS + "NextMarker")
        marker = nm.text if nm is not None else keys[-1]


def rows_of(key):
    body = get(BASE + key)
    if body is None:
        return []
    out = []
    with zipfile.ZipFile(io.BytesIO(body)) as z:
        for line in z.read(z.namelist()[0]).decode().splitlines():
            p = line.split(",")
            if not p[0].isdigit():
                continue
            ts = int(p[0])
            if ts > 10 ** 14:  # microseconds since 2025
                ts //= 1000
            out.append((ts // 86400000, p[1], p[2], p[3], p[4], p[7], p[8], p[10]))
    return out


def symbol(sym):
    keys = [k for k in listing(f"data/spot/monthly/klines/{sym}/1d/", False) if k.endswith(".zip") and k[-11:-4] >= FROM]
    last = max((k[-11:-4] for k in keys), default="0000-00")
    daily = [k for k in listing(f"data/spot/daily/klines/{sym}/1d/", False) if k.endswith(".zip") and k[-14:-7] > last]
    seen = {}
    for k in keys + daily:
        for r in rows_of(k):
            seen[r[0]] = r
    return sym, [seen[d] for d in sorted(seen)]


def g(x):
    return ("%.7g" % float(x))


def main():
    out = sys.argv[1]
    syms = [p.split("/")[-2] for p in listing("data/spot/monthly/klines/")]
    syms = [s for s in syms if s.endswith("USDT")]
    print("symbols", len(syms), flush=True)
    n = 0
    with gzip.open(out + "/binance-1d.csv.gz", "wt", newline="") as f, ThreadPoolExecutor(16) as ex:
        w = csv.writer(f)
        w.writerow(["sym", "day", "o", "h", "l", "c", "qv", "n", "tbqv"])
        for sym, rows in ex.map(symbol, syms):
            for r in rows:
                w.writerow([sym, r[0], g(r[1]), g(r[2]), g(r[3]), g(r[4]), g(r[5]), r[6], g(r[7])])
            n += len(rows)
    print("rows", n, flush=True)
    chains = ["all", "Ethereum", "Tron", "BSC", "Solana", "Arbitrum", "Avalanche", "Polygon", "Optimism", "Base", "Sui",
              "Aptos", "Near", "TON"]
    st = {}
    for c in chains:
        body = get("https://stablecoins.llama.fi/stablecoincharts/" + c)
        if body:
            st[c] = [[int(x["date"]) // 86400, round(x.get("totalCirculatingUSD", {}).get("peggedUSD", 0))] for x in json.loads(body)]
        time.sleep(1)
    with gzip.open(out + "/stablecoins.json.gz", "wt") as f:
        json.dump(st, f)
    print("stablecoin chains", list(st), flush=True)


main()
