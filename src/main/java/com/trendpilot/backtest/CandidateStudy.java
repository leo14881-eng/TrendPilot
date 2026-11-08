package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.trendpilot.config.StrategyParams;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.variant.VariantOverrides;

/**
 * 配置化变体的回测与自动发布前检查（H1/H2/H4 研究 2026-10-26，以及每周六的自动回测）。
 * 基准 = 线上现行 V2（ATR×1.5 止损 + 3 天等反弹 + 冻结修复，买入审核不加门槛）；每个候选只叠加一组 {@link VariantOverrides}，
 * 判断代码和线上变体账本是同一份。每个候选配一个同规则的随机对照（同一轮、同笔数、同金额，币换成同币池随机一个）。
 * 输出 report.md（对比表）和 candidates.json（每个候选的检查结论；status = PASS 的才会被服务器拿去作为影子变体上线）。
 * <pre>
 * java -cp ... com.trendpilot.backtest.CandidateStudy 数据目录 输出目录 起始月 结束月 最后一天 开始交易 验证期开始 线程数
 *      [名字:key=v,key=v ...]   （不给候选时用 {@link #weeklyGrid()}）
 * </pre>
 * 环境变量 EVIDENCE_URL：结果目录在 GitHub 上的链接，写进 candidates.json 作为证据链接。
 */
public final class CandidateStudy {

    /**
     * 候选：名字 + 参数改动 + 属于哪个假设。config = 直接覆盖的线上策略参数（键相对 trendpilot.strategy，例如 risk.loss-exits-freeze），
     * discoverEvery = 买入审核间隔（15m 根数，4 = 每小时）。这两项只在回测里用，服务器不会把带它们的候选自动上线。
     */
    public record Candidate(String id, String group, VariantOverrides overrides, Map<String, Object> config, Map<String, String> sim) {

        Candidate(String id, String group, VariantOverrides overrides) {
            this(id, group, overrides, Map.of(), Map.of());
        }

        int discoverEvery() {
            return Integer.parseInt(sim.getOrDefault("discover-every", "4"));
        }

        boolean liveCapable() {
            return config.isEmpty() && sim.isEmpty();
        }

        /** 按候选的回测专用设置调整一个模拟（审核频率、涨幅榜动量、固定止损、ATR 跟踪止损）。 */
        B0Sim apply(B0Sim b) {
            b.overrides(overrides).discoverEvery(discoverEvery());
            if (sim.containsKey("momentum-top")) {
                b.momentum(Integer.parseInt(sim.get("momentum-top")), Integer.parseInt(sim.getOrDefault("momentum-hours", "24")) * 4);
            }
            // 变体参数 stop-fixed-pct（线上 V6BS6 用）和回测专用 sim.stop-fixed 是同一个固定止损
            if (overrides.hasFixedStop()) b.stop(StopRule.fixed("固定" + overrides.toMap().get("stop-fixed-pct") + "%", overrides.stopFixedPct(), "HOUR"));
            if (sim.containsKey("stop-fixed")) b.stop(StopRule.fixed("固定" + sim.get("stop-fixed") + "%", Double.parseDouble(sim.get("stop-fixed")), "HOUR"));
            if (sim.containsKey("trail-atr")) b.trailAtr(Double.parseDouble(sim.get("trail-atr")));
            if (sim.containsKey("max-ret4h-pct")) b.chaseCap(Double.parseDouble(sim.get("max-ret4h-pct")));
            if (sim.containsKey("wick")) b.wick(Double.parseDouble(sim.get("wick")));
            if (sim.containsKey("enforce-max-open")) b.enforceMaxOpen(Boolean.parseBoolean(sim.get("enforce-max-open")) || sim.get("enforce-max-open").equals("1"));
            if (sim.containsKey("stale-hours")) b.staleExit(Integer.parseInt(sim.get("stale-hours")), false, 0);
            if (sim.containsKey("runner-regimes")) {
                b.runnerRegimes(java.util.Arrays.stream(sim.get("runner-regimes").split("\\+")).map(Integer::valueOf)
                        .collect(java.util.stream.Collectors.toSet()));
            }
            return b;
        }

        /** 发布前检查用哪个候选当基准（sim.base），没写就是"现行"。 */
        String baseId() {
            return sim.getOrDefault("base", "现行");
        }
    }

    /** 解析 "key=v,key=v"：cfg.* 进 config（线上策略参数），sim.* 进回测专用设置，其余是变体参数。 */
    static Candidate parse(String id, String group, String inline) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        Map<String, String> sim = new LinkedHashMap<>();
        List<String> rest = new ArrayList<>();
        for (String kv : inline.split(",")) {
            int eq = kv.indexOf('=');
            if (kv.startsWith("cfg.")) {
                cfg.put("trendpilot.strategy." + kv.substring(4, eq), kv.substring(eq + 1));
            } else if (kv.startsWith("sim.")) {
                sim.put(kv.substring(4, eq), kv.substring(eq + 1));
            } else if (!kv.isBlank()) {
                rest.add(kv);
            }
        }
        return new Candidate(id, group, VariantOverrides.parseInline(String.join(",", rest)), cfg, sim);
    }

    /**
     * 放宽限制（用户 2026-10-26："V2 太严格的话可以去掉部分限制"）：逐项单独放宽，再测"全部放宽 + 更好的退出"组合，共 8 组。
     * 冻结放宽（不冻结 / 只减半）在 flow 里。
     */
    static List<Candidate> relax() {
        String regime = "cfg.capital.regime-limits[0].regime=RISK_OFF,cfg.capital.regime-limits[0].pct=0.7,"
                + "cfg.capital.regime-limits[1].regime=NEUTRAL,cfg.capital.regime-limits[1].pct=0.85,"
                + "cfg.capital.regime-limits[2].regime=CONSTRUCTIVE,cfg.capital.regime-limits[2].pct=0.95,"
                + "cfg.capital.regime-limits[3].regime=STRONG,cfg.capital.regime-limits[3].pct=1.0,cfg.capital.normal-pool=20000";
        String all = "cfg.risk.loss-exits-freeze=999,cfg.risk.loss-exits-half-budget=999,cfg.review.min-score=7,min-depth-usdt=15000," + regime;
        List<Candidate> c = new ArrayList<>();
        c.add(parse("L1_深度15000", "relax", "min-depth-usdt=15000"));
        c.add(parse("L2_不减半额度", "relax", "cfg.risk.loss-exits-half-budget=999"));
        c.add(parse("L3_行情上限放宽", "relax", regime));
        c.add(parse("L4_评分门槛7", "relax", "cfg.review.min-score=7"));
        c.add(parse("L5_全部放宽", "relax", all));
        c.add(parse("L6_全部放宽+S1_L12", "relax", all + ",exit-rule=S1_L12"));
        c.add(parse("L7_全部放宽+C1_L15_T24", "relax", all + ",exit-rule=C1_L15_T24"));
        c.add(parse("L8_全部放宽+浮盈5%后回吐3%", "relax", all + ",runner-arm-pct=5,runner-giveback-pct=3"));
        return c;
    }

    /**
     * 实时涨幅榜动量（用户 2026-10-26）：按滚动 24h / 4h 涨幅取前 N 名，满足深度和滑点就买（每笔 1,000U），共 10 组。
     * 退出：现行 V2 退出、C1_L15_T24、浮盈 5% 后宽跟踪、固定止损 5% + ATR×2 跟踪。
     */
    static List<Candidate> momentum() {
        List<Candidate> c = new ArrayList<>();
        for (int h : new int[]{24, 4}) {
            for (int n : new int[]{3, 5, 10}) {
                c.add(parse("M_" + h + "h前" + n + "_V2退出", "momentum", "sim.momentum-top=" + n + ",sim.momentum-hours=" + h));
            }
        }
        c.add(parse("M_24h前5_C1_L15_T24", "momentum", "sim.momentum-top=5,sim.momentum-hours=24,exit-rule=C1_L15_T24"));
        c.add(parse("M_24h前5_浮盈5%后回吐3%", "momentum", "sim.momentum-top=5,sim.momentum-hours=24,runner-arm-pct=5,runner-giveback-pct=3"));
        c.add(parse("M_24h前5_止损5%+ATR×2跟踪", "momentum", "sim.momentum-top=5,sim.momentum-hours=24,sim.stop-fixed=5,sim.trail-atr=2"));
        c.add(parse("M_24h前5_每15分钟", "momentum", "sim.momentum-top=5,sim.momentum-hours=24,sim.discover-every=1"));
        return c;
    }

    /**
     * 涨幅榜覆盖研究（2026-10-26，周复盘第 3 节：为什么涨幅榜的币没进 V2、进了也没吃到）：
     * 冻结放宽 4 组、让赢家跑 4 组、每 15 分钟审核 1 组，共 9 组。
     */
    static List<Candidate> flow() {
        List<Candidate> c = new ArrayList<>();
        c.add(parse("F1_不冻结不减半", "freeze", "cfg.risk.loss-exits-freeze=999,cfg.risk.loss-exits-half-budget=999"));
        c.add(parse("F2_只减半不冻结", "freeze", "cfg.risk.loss-exits-freeze=999"));
        c.add(parse("F3_冻结窗口12h", "freeze", "cfg.risk.loss-window-hours=12"));
        c.add(parse("F4_冻结门槛5笔", "freeze", "cfg.risk.loss-exits-freeze=5"));
        c.add(parse("R1_浮盈5%后回吐3%", "runner", "runner-arm-pct=5,runner-giveback-pct=3"));
        c.add(parse("R2_浮盈5%后回吐5%", "runner", "runner-arm-pct=5,runner-giveback-pct=5"));
        c.add(parse("R3_浮盈8%后回吐4%", "runner", "runner-arm-pct=8,runner-giveback-pct=4"));
        c.add(parse("R4_浮盈8%后回吐6%", "runner", "runner-arm-pct=8,runner-giveback-pct=6"));
        c.add(parse("D1_每15分钟审核", "delay", "sim.discover-every=1"));
        return c;
    }

    /** H1/H2/H4 研究（2026-10-26）登记的方案，看结果之前定好，共 14 组。 */
    static List<Candidate> h124() {
        List<Candidate> c = new ArrayList<>();
        for (String act : new String[]{"SKIP", "HALF"}) {
            String a = act.equals("SKIP") ? "不开" : "半仓";
            c.add(cand("H1a_BTC24h>1.5_" + a, "H1", "btc24h-above-pct=1.5,hot-entry=" + act));
            c.add(cand("H1b_币24h5-10_" + a, "H1", "coin24h-band-pct=5-10,hot-entry=" + act));
            c.add(cand("H1c_两者任一_" + a, "H1", "btc24h-above-pct=1.5,coin24h-band-pct=5-10,hot-entry=" + act));
        }
        for (int h : new int[]{12, 24}) {
            for (int x : new int[]{2, 4, 6}) {
                c.add(cand("H2_" + h + "h_浮盈<1%_亏>" + x + "%", "H2",
                        "early-exit-hours=" + h + ",early-exit-max-mfe-pct=1,early-exit-loss-pct=" + x));
            }
        }
        c.add(cand("H4_放量≤4倍", "H4", "max-volume-accel=4"));
        c.add(cand("H4_放量≤6倍", "H4", "max-volume-accel=6"));
        return c;
    }

    /** 离线退出回放 / 影子并行里表现好的退出规则（2026-10-26 信号跟踪周报），复用 tracking.ExitRule 同一份实现，共 5 组。 */
    static List<Candidate> exitRules() {
        List<Candidate> c = new ArrayList<>();
        for (String id : new String[]{"C1_L15_T24", "C1_L20_T24", "C1_L25_T24", "S1_L12", "S1_L15"}) {
            c.add(cand("X_" + id, "exit-rule", "exit-rule=" + id));
        }
        return c;
    }

    /**
     * 极端追高上限（周复盘 2026-11-01 优化 O1，看结果之前定好）：V5 规则（rel1h ≥ 5.855，每笔 1,000U）和
     * V6 规则（24h 涨幅前 3，最多 17 个）各一个基准，各叠加"买入前 4h 涨幅 ≥ 15% / ≥ 20% 不开"，共 4 组 + 2 个基准。
     * 4 组的发布前检查以各自的基准为准（sim.base），随机对照挑币时也套同一个上限。只回测，不会被服务器自动上线（带 sim.*）。
     */
    static List<Candidate> chase() {
        String v5 = "min-buy-rel1h=5.855";
        String v6 = "momentum-top=3,momentum-hours=24,max-open=17";
        List<Candidate> c = new ArrayList<>();
        c.add(parse("O1_V5基准", "O1", v5));
        c.add(parse("O1_V6基准", "O1", v6));
        for (int cap : new int[]{15, 20}) {
            c.add(parse("O1_V5_4h≥" + cap + "%不开", "O1", v5 + ",sim.max-ret4h-pct=" + cap + ",sim.base=O1_V5基准"));
            c.add(parse("O1_V6_4h≥" + cap + "%不开", "O1", v6 + ",sim.max-ret4h-pct=" + cap + ",sim.base=O1_V6基准"));
        }
        return c;
    }

    /**
     * V6 买点重新测卖法（用户 2026-11-02，优化 O3，看结果之前定好）：V6 买入规则（24h 涨幅前 3，最多 17 个）不变，只换卖法，共 6 组：
     * 固定止损 4% / 6%（替代 ATR×1.5 止损，1h 收盘确认，利润保护照旧）；浮盈达本金 5% 后改为从最高净利回撤本金 3% / 5% 卖
     * （替代 60% 锁定和 1% 回吐，止损照旧）；固定止损 5% + 浮盈 5% 后回撤 4%；持有 12h 仍浮亏按 1h 收盘卖（替代 72h 等反弹）。
     * 发布前检查对 V6 现行卖法（O3_V6现行卖法，和 O1_V6基准同一规则）。
     */
    static List<Candidate> v6Exit() {
        String v6 = "momentum-top=3,momentum-hours=24,max-open=17";
        String base = ",sim.base=O3_V6现行卖法";
        List<Candidate> c = new ArrayList<>();
        c.add(parse("O3_V6现行卖法", "O3", v6));
        c.add(parse("O3_止损4%", "O3", v6 + ",sim.stop-fixed=4" + base));
        c.add(parse("O3_止损6%", "O3", v6 + ",sim.stop-fixed=6" + base));
        c.add(parse("O3_浮盈5%后回撤3%", "O3", v6 + ",runner-arm-pct=5,runner-giveback-pct=3" + base));
        c.add(parse("O3_浮盈5%后回撤5%", "O3", v6 + ",runner-arm-pct=5,runner-giveback-pct=5" + base));
        c.add(parse("O3_止损5%+浮盈5%后回撤4%", "O3", v6 + ",sim.stop-fixed=5,runner-arm-pct=5,runner-giveback-pct=4" + base));
        c.add(parse("O3_12h仍浮亏就卖", "O3", v6 + ",sim.stale-hours=12" + base));
        return c;
    }

    /**
     * 按行情切换止盈（用户 2026-11-02 "不要止盈太早"，优化 O4，看结果之前定好）：V6 买入规则不变，只在"普涨""山寨强于 BTC"行情
     * （Regimes 1、2，每个整点只用当时已知的数据）下改用宽跟踪，其他行情照旧，共 5 组：浮盈达本金 5% / 10% 后从最高净利回撤
     * 本金 8% / 12% 才卖（4 组），以及浮盈 5% 后只保本（底线 0.35%）+ 1h 收盘低于最高价 3×日线 ATR 卖（1 组）。
     */
    static List<Candidate> strongExit() {
        String v6 = "momentum-top=3,momentum-hours=24,max-open=17";
        String gate = ",sim.runner-regimes=1+2,sim.base=O4_V6现行卖法";
        List<Candidate> c = new ArrayList<>();
        c.add(parse("O4_V6现行卖法", "O4", v6));
        for (int arm : new int[]{5, 10}) {
            for (int give : new int[]{8, 12}) {
                c.add(parse("O4_强势时浮盈" + arm + "%后回撤" + give + "%", "O4",
                        v6 + ",runner-arm-pct=" + arm + ",runner-giveback-pct=" + give + gate));
            }
        }
        c.add(parse("O4_强势时浮盈5%后ATR×3跟踪", "O4", v6 + ",runner-arm-pct=5,runner-giveback-pct=100,sim.trail-atr=3" + gate));
        return c;
    }

    /**
     * 过拟合检查（用户 2026-11-02）：参数邻域。V6 = 涨幅榜前 2/3/4/5 × 回看 12/24/48h（12 组，回测专用写法 sim.momentum-*）；
     * V5 = rel1h ≥ 4 / 4.5 / 5 / 5.855 / 6.5 / 7.5（6 组，每笔 1,000U）。共 18 组；随机对照的逐笔明细也输出，供滚动前推检验用。
     */
    static List<Candidate> robust() {
        List<Candidate> c = new ArrayList<>();
        for (int h : new int[]{12, 24, 48}) {
            for (int n : new int[]{2, 3, 4, 5}) {
                c.add(parse("R_V6_前" + n + "_" + h + "h", "robust", "sim.momentum-top=" + n + ",sim.momentum-hours=" + h));
            }
        }
        for (String r : new String[]{"4", "4.5", "5", "5.855", "6.5", "7.5"}) {
            c.add(parse("R_V5_rel1h≥" + r, "robust", "min-buy-rel1h=" + r));
        }
        return c;
    }

    /** 每周自动回测的小格子（H1/H2/H4 的代表档、两条退出规则 + rel1h 与每笔金额邻域），共 13 组。 */
    static List<Candidate> weeklyGrid() {
        List<Candidate> c = new ArrayList<>();
        c.add(cand("W_H1_BTC24h>1.5_半仓", "H1", "btc24h-above-pct=1.5,hot-entry=HALF"));
        c.add(cand("W_H1_币24h5-10_半仓", "H1", "coin24h-band-pct=5-10,hot-entry=HALF"));
        c.add(cand("W_H2_24h_浮盈<1%_亏>4%", "H2", "early-exit-hours=24,early-exit-max-mfe-pct=1,early-exit-loss-pct=4"));
        c.add(cand("W_H2_12h_浮盈<1%_亏>4%", "H2", "early-exit-hours=12,early-exit-max-mfe-pct=1,early-exit-loss-pct=4"));
        c.add(cand("W_H4_放量≤4倍", "H4", "max-volume-accel=4"));
        c.add(cand("W_C1_L15_T24", "exit-rule", "exit-rule=C1_L15_T24"));
        c.add(cand("W_S1_L12", "exit-rule", "exit-rule=S1_L12"));
        c.add(cand("W_M_24h前3", "momentum", "momentum-top=3,momentum-hours=24,max-open=17"));
        c.add(cand("W_rel1h≥4.5", "rel1h", "min-buy-rel1h=4.5"));
        c.add(cand("W_rel1h≥5.855", "rel1h", "min-buy-rel1h=5.855"));
        c.add(cand("W_rel1h≥7.5", "rel1h", "min-buy-rel1h=7.5"));
        c.add(cand("W_rel1h≥5.855_每笔1500", "size", "min-buy-rel1h=5.855,trade-usdt=1500"));
        c.add(cand("W_每笔1250", "size", "trade-usdt=1250"));
        return c;
    }

    private static Candidate cand(String id, String group, String inline) {
        return new Candidate(id, group, VariantOverrides.parseInline(inline));
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        YearMonth from = YearMonth.parse(args[2]);
        YearMonth to = YearMonth.parse(args[3]);
        LocalDate lastDay = args[4].equals("-") ? null : LocalDate.parse(args[4]);
        LocalDate tradeFrom = LocalDate.parse(args[5]);
        LocalDate split = LocalDate.parse(args[6]);
        int threads = Integer.parseInt(args[7]);
        List<Candidate> cands = new ArrayList<>();
        String gridName = "weekly";
        for (int a = 8; a < args.length; a++) {
            if (args[a].equals("relax") || args[a].equals("momentum")) {
                cands.addAll(args[a].equals("relax") ? relax() : momentum());
                gridName = args[a];
                continue;
            }
            if (args[a].equals("flow")) {
                cands.addAll(flow());
                gridName = "flow";
                continue;
            }
            if (args[a].equals("exitrules")) {
                cands.addAll(exitRules());
                gridName = "exitrules";
                continue;
            }
            if (args[a].equals("robust")) {
                cands.addAll(robust());
                gridName = "robust";
                continue;
            }
            if (args[a].equals("strongexit")) {
                cands.addAll(strongExit());
                gridName = "strongexit";
                continue;
            }
            if (args[a].equals("v6exit")) {
                cands.addAll(v6Exit());
                gridName = "v6exit";
                continue;
            }
            if (args[a].equals("chase")) {
                cands.addAll(chase());
                gridName = "chase";
                continue;
            }
            if (args[a].equals("h124")) {
                cands.addAll(h124());
                gridName = "h124";
                continue;
            }
            int colon = args[a].indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("候选要写成 名字:key=v,key=v，看不懂：" + args[a]);
            cands.add(parse(args[a].substring(0, colon), "custom", args[a].substring(colon + 1)));
            gridName = "custom";
        }
        if (cands.isEmpty()) cands = weeklyGrid();
        Files.createDirectories(outDir);

        StrategyParams params = BacktestMain.loadParams();
        Set<String> excluded = new HashSet<>();
        Archive archive = new Archive(dataDir);
        List<String> symbols = new ArrayList<>();
        for (String s : archive.usdtSymbols()) {
            String base = s.substring(0, s.length() - 4);
            if (com.trendpilot.universe.UniverseFilter.excludedOffline(params.universe(), s)) {
                excluded.add(s);
                continue;
            }
            symbols.add(s);
        }
        archive.download(symbols, from, to, lastDay);
        long start = from.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        LocalDate endDay = lastDay != null ? lastDay.plusDays(1) : to.plusMonths(1).atDay(1);
        long end = endDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int size = (int) ((end - start) / Series.Q);
        Map<String, Series> series = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    Series x = archive.load(s, start, size, from, to, lastDay);
                    if (x != null) series.put(s, x);
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        MarketData data = new MarketData(start, size, series, 150, excluded);
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        int fromQ = q(data, tradeFrom);
        int splitQ = q(data, split);

        List<B0Sim> todo = new ArrayList<>();
        todo.add(EntryStudy.live("现行", data, regimes, tape, params, fromQ, size));
        todo.add(EntryStudy.live("随机1", data, regimes, tape, params, fromQ, size).randomEntry(1001));
        todo.add(EntryStudy.live("随机2", data, regimes, tape, params, fromQ, size).randomEntry(1002));
        int seed = 5000;
        for (Candidate c : cands) {
            StrategyParams pp = c.config().isEmpty() ? params : BacktestMain.loadParams(c.config());
            todo.add(c.apply(EntryStudy.live(c.id(), data, regimes, tape, pp, fromQ, size)));
            todo.add(c.apply(EntryStudy.live(c.id() + " 随机", data, regimes, tape, pp, fromQ, size)).randomEntry(seed++));
        }
        Map<String, Sim> byName = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Sim>> fs = new ArrayList<>();
            for (B0Sim sim : todo) {
                fs.add(pool.submit(() -> {
                    sim.run();
                    System.out.println("完成 " + sim.name() + "：" + sim.trades().size() + " 笔");
                    return sim;
                }));
            }
            for (Future<Sim> f : fs) {
                Sim s = f.get();
                byName.put(s.name(), s);
            }
        }
        List<Sim> sims = new ArrayList<>(byName.values());
        Sim base = byName.get("现行");
        ReleaseCheck.Period bF = ReleaseCheck.Period.of(base, regimes, fromQ, size, params.capital().hardCap());
        ReleaseCheck.Period bV = ReleaseCheck.Period.of(base, regimes, splitQ, size);
        double[] bReg = Metrics.of(base, regimes, fromQ, size).regimeEquity();

        String evidence = System.getenv().getOrDefault("EVIDENCE_URL", "");
        List<Map<String, Object>> out = new ArrayList<>();
        StringBuilder md = new StringBuilder();
        md.append("# 配置化变体回测与发布前检查（").append(tradeFrom).append(" ～ ").append(endDay.minusDays(1)).append("）\n\n");
        md.append("基准 = 线上现行 V2（ATR×1.5 止损 + 3 天等反弹 + 冻结修复）。校准期 ").append(tradeFrom).append(" ～ ")
                .append(split.minusDays(1)).append("，验证期 ").append(split).append(" ～ ").append(endDay.minusDays(1))
                .append("（最后 16 个月，不参与调参）。候选 ").append(cands.size()).append(" 组（").append(gridName)
                .append("），每组配一个同规则随机对照。\n\n");
        md.append("## 发布前检查\n\n");
        md.append("| 候选 | 改动 | 结论 | 验证期 权益/回撤 | 全期 权益/回撤 | 净值回撤 | 卡玛 | 笔数 | 每笔差 ±95%（对现行） | 每笔差 ±95%（对随机） | 不满足的检查 |\n");
        md.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
        md.append(String.format(Locale.ROOT, "| 现行 | — | 基准 | %,.0f / %,.0f | %,.0f / %,.0f | %.1f%% | %.1f | %d | — | — | — |%n",
                bV.equity(), bV.maxDd(), bF.equity(), bF.maxDd(), bF.navDd() * 100, bF.calmar(), bF.trades()));
        StringBuilder vsBase = new StringBuilder();
        for (Candidate c : cands) {
            Sim s = byName.get(c.id());
            ReleaseCheck.Period f = ReleaseCheck.Period.of(s, regimes, fromQ, size, params.capital().hardCap());
            ReleaseCheck.Period v = ReleaseCheck.Period.of(s, regimes, splitQ, size);
            ReleaseCheck.Period rnd = ReleaseCheck.Period.of(byName.get(c.id() + " 随机"), regimes, fromQ, size);
            Sim cb = byName.getOrDefault(c.baseId(), base);
            ReleaseCheck.Period cbF = cb == base ? bF : ReleaseCheck.Period.of(cb, regimes, fromQ, size, params.capital().hardCap());
            ReleaseCheck.Period cbV = cb == base ? bV : ReleaseCheck.Period.of(cb, regimes, splitQ, size);
            double[] cbReg = cb == base ? bReg : Metrics.of(cb, regimes, fromQ, size).regimeEquity();
            double[] cReg = Metrics.of(s, regimes, fromQ, size).regimeEquity();
            ReleaseCheck.Result r = ReleaseCheck.evaluate(f, v, cbF, cbV, cReg, cbReg, rnd);
            if (cb != base) vsBase.append(baseRow(c.id(), cb.name(), f, v, cbF, cbV, cReg, cbReg));
            List<String> failed = r.checks().entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
            List<String> why = new ArrayList<>(failed);
            why.addAll(r.notes());
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %,.0f / %,.0f | %,.0f / %,.0f | %.1f%% | %.1f | %d | %+.2f ±%.2f | %+.2f ±%.2f | %s |%n",
                    c.id(), describe(c), label(r.verdict()), v.equity(), v.maxDd(), f.equity(), f.maxDd(), f.navDd() * 100, f.calmar(), f.trades(),
                    r.perTradeDiff()[0], r.perTradeDiff()[1], r.randomDiff()[0], r.randomDiff()[1],
                    why.isEmpty() ? "—" : String.join("；", why)));
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("id", c.id());
            j.put("group", c.group());
            j.put("overrides", c.overrides().toMap());
            j.put("config", c.config());
            j.put("sim", c.sim());
            j.put("liveCapable", c.liveCapable());
            j.put("status", r.verdict().name());
            j.put("checks", r.checks());
            j.put("full", Map.of("equity", round(f.equity()), "maxDrawdown", round(f.maxDd()), "trades", f.trades(), "perTrade", round(f.meanPnl()),
                    "navDrawdownPct", round(f.navDd() * 100), "calmar", round(f.calmar())));
            j.put("validation", Map.of("equity", round(v.equity()), "maxDrawdown", round(v.maxDd()), "trades", v.trades(), "perTrade", round(v.meanPnl())));
            j.put("evidence", evidence.isBlank() ? "" : evidence + "/report.md");
            out.add(j);
        }
        if (vsBase.length() > 0) {
            md.append("\n### 对各自基准（不是现行 V2）\n\n")
                    .append("| 候选 | 基准 | 全期 权益 候选 / 基准 | 验证期 权益 候选 / 基准 | 全期回撤 候选 / 基准 | 验证期回撤 候选 / 基准 | 净值回撤 | ")
                    .append(String.join(" | ", java.util.Arrays.asList(Regimes.NAMES).subList(1, 7))).append("（候选 / 基准，U） |\n")
                    .append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n").append(vsBase);
            md.append("\n").append(chaseBuckets(sims.stream().filter(x -> !x.name().contains("随机")).toList(), data, fromQ, splitQ));
            md.append("\n").append(payoffTable(sims.stream().filter(x -> !x.name().contains("随机")).toList(), fromQ, splitQ));
            md.append("\n").append(bigWins(sims.stream().filter(x -> !x.name().contains("随机")).toList(), regimes, fromQ));
            md.append("\n").append(windows(sims.stream().filter(x -> !x.name().contains("随机")).toList(), data));
            md.append("\n").append(altMarket(data, fromQ));
            md.append("\n").append(captureTable(sims.stream().filter(x -> !x.name().contains("随机")).toList(), params));
        }
        md.append("\n结论：PASS = 全部检查通过（先作为影子变体跑满 30 天，再按自动转正条件决定）；PROMISING = 有希望但没全部通过（作为影子变体观察）；")
                .append("WORSE = 明显更差（只存档）。检查项见 ReleaseCheck。\n\n");
        md.append(AsymStudy.splitTable(sims, regimes, fromQ, splitQ, size));
        md.append(AsymStudy.asymmetry(sims.stream().filter(s -> !s.name().contains("随机")).toList(), fromQ));
        Nav nav = new Nav(data, params.capital().hardCap());
        md.append(StopStudy.report(sims, data, regimes, nav, fromQ, size, tradeFrom + " ～ " + endDay.minusDays(1), tradeFrom, endDay)
                .replaceFirst("# 防洗盘止损检验（[^）]*）\\n\\n", "## 发布前检查用的完整对照\n\n"));
        Files.writeString(outDir.resolve("report.md"), md.toString());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", 1);
        root.put("generatedAt", Instant.now().toString());
        root.put("grid", gridName);
        root.put("strategyVersion", params.version());
        root.put("dataFrom", tradeFrom.toString());
        root.put("dataTo", endDay.minusDays(1).toString());
        root.put("validationFrom", split.toString());
        root.put("evidence", evidence);
        root.put("baseline", Map.of("full", Map.of("equity", round(bF.equity()), "maxDrawdown", round(bF.maxDd()), "trades", bF.trades()),
                "validation", Map.of("equity", round(bV.equity()), "maxDrawdown", round(bV.maxDd()), "trades", bV.trades())));
        root.put("candidates", out);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(outDir.resolve("candidates.json").toFile(), root);
        for (Sim s : sims) {
            if (s.name().contains("随机") && !gridName.equals("robust")) continue;
            Files.writeString(outDir.resolve("trades-" + s.name().replaceAll("[^\\p{L}\\p{N}]+", "_") + ".csv"), BacktestMain.tradesCsv(s, data));
        }
        System.out.println(md);
    }

    private static String baseRow(String id, String baseName, ReleaseCheck.Period f, ReleaseCheck.Period v,
                                  ReleaseCheck.Period bf, ReleaseCheck.Period bv, double[] reg, double[] breg) {
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "| %s | %s | %,.0f / %,.0f | %,.0f / %,.0f | %,.0f / %,.0f | %,.0f / %,.0f | %.1f%% |",
                id, baseName, f.equity(), bf.equity(), v.equity(), bv.equity(), f.maxDd(), bf.maxDd(), v.maxDd(), bv.maxDd(), f.navDd() * 100));
        for (int r = 1; r <= 6; r++) b.append(String.format(Locale.ROOT, " %,.0f / %,.0f |", reg[r], breg[r]));
        return b.append('\n').toString();
    }

    /** 买入前 4h 涨幅分档（同一口径 {@link B0Sim#ret4hPct}）的每笔期望：全期和验证期，每笔 U ±95%。 */
    static String chaseBuckets(List<Sim> sims, MarketData data, int fromQ, int splitQ) {
        double[] edges = {Double.NEGATIVE_INFINITY, 5, 10, 15, 20, Double.POSITIVE_INFINITY};
        String[] names = {"< 5%", "5%～10%", "10%～15%", "15%～20%", "≥ 20%", "缺数据"};
        StringBuilder b = new StringBuilder("### 按买入前 4h 涨幅分档的每笔期望（U，±95%；括号里是笔数）\n\n| 方案 | 期间 | ")
                .append(String.join(" | ", names)).append(" |\n|---|---|").append("---:|".repeat(names.length)).append('\n');
        for (Sim s : sims) {
            for (int part = 0; part < 2; part++) {
                List<List<Double>> bins = new ArrayList<>();
                for (int i = 0; i < names.length; i++) bins.add(new ArrayList<>());
                for (Trade t : s.trades()) {
                    if (t.entryQ() < (part == 0 ? fromQ : splitQ)) continue;
                    double r = B0Sim.ret4hPct(data.get(t.symbol()), t.entryQ());
                    int k = names.length - 1;
                    if (!Double.isNaN(r)) for (k = 0; k < edges.length - 1 && !(r >= edges[k] && r < edges[k + 1]); k++) ;
                    bins.get(k).add(t.pnl());
                }
                b.append("| ").append(s.name()).append(" | ").append(part == 0 ? "全期" : "验证期").append(" |");
                for (List<Double> x : bins) {
                    if (x.isEmpty()) {
                        b.append(" — |");
                        continue;
                    }
                    double m = x.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
                    double v = x.stream().mapToDouble(y -> (y - m) * (y - m)).sum() / Math.max(1, x.size() - 1);
                    b.append(String.format(Locale.ROOT, " %+.2f ±%.2f (%d) |", m, 1.96 * Math.sqrt(v / x.size()), x.size()));
                }
                b.append('\n');
            }
        }
        return b.toString();
    }

    /**
     * 盈利单卖出时拿到最大浮盈的比例（卖出净利 / 持仓期间最大可退出净利），按卖出原因分；利润保护卖出另算
     * "卖出净利 / 卖出时的保护底线"（底线 = max(本金 × floor%, 最大净利 − 本金 × giveback%, 最大净利 × keep%)），
     * 用来和线上的 52%～57% 对照：回测按底线价成交（FastWatch 口径），线上要等下一次检查、按真实买盘成交。
     */
    static String captureTable(List<Sim> sims, StrategyParams params) {
        StringBuilder b = new StringBuilder("### 盈利单拿到最大浮盈的比例（中位数 / 均值）\n\n")
                .append("| 方案 | 盈利单 | 卖出 / 最大浮盈 中位 | 均值 | 其中利润保护卖出 | 利润保护：卖出 / 最大浮盈 中位 | 利润保护：卖出 / 保护底线 中位 | 最大浮盈中位（本金 %） |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Sim s : sims) {
            List<Double> all = new ArrayList<>(), pp = new ArrayList<>(), floor = new ArrayList<>(), peak = new ArrayList<>();
            for (Trade t : s.trades()) {
                if (t.pnl() <= 0 || t.maxNet() <= 0) continue;
                all.add(t.pnl() / t.maxNet());
                peak.add(t.maxNet() / t.cost() * 100);
                if (t.reason().contains("PROTECT")) {
                    pp.add(t.pnl() / t.maxNet());
                    double fl = protectFloor(params, t.cost(), t.maxNet());
                    if (fl > 0) floor.add(t.pnl() / fl);
                }
            }
            b.append(String.format(Locale.ROOT, "| %s | %d | %.0f%% | %.0f%% | %d | %.0f%% | %.0f%% | %.2f%% |%n", s.name(), all.size(),
                    median(all) * 100, all.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN) * 100,
                    pp.size(), median(pp) * 100, median(floor) * 100, median(peak)));
        }
        return b.toString();
    }

    /** 盈亏比和每笔期望（全期 / 验证期）：胜率、平均盈利、平均亏损、盈亏比 = 平均盈利 / |平均亏损|、每笔 U ±95%。 */
    static String payoffTable(List<Sim> sims, int fromQ, int splitQ) {
        StringBuilder b = new StringBuilder("### 盈亏比和每笔期望\n\n| 方案 | 期间 | 笔数 | 胜率 | 平均盈利 U | 平均亏损 U | 盈亏比 | 每笔 U ±95% |\n|---|---|---:|---:|---:|---:|---:|---:|\n");
        for (Sim s : sims) {
            for (int part = 0; part < 2; part++) {
                int from = part == 0 ? fromQ : splitQ;
                List<Double> x = s.trades().stream().filter(t -> t.entryQ() >= from).map(Trade::pnl).toList();
                if (x.isEmpty()) continue;
                double win = x.stream().filter(v -> v > 0).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
                double loss = x.stream().filter(v -> v <= 0).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
                long wins = x.stream().filter(v -> v > 0).count();
                double m = x.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
                double var = x.stream().mapToDouble(v -> (v - m) * (v - m)).sum() / Math.max(1, x.size() - 1);
                b.append(String.format(Locale.ROOT, "| %s | %s | %d | %.1f%% | %+.2f | %+.2f | %.2f | %+.2f ±%.2f |%n", s.name(),
                        part == 0 ? "全期" : "验证期", x.size(), 100.0 * wins / x.size(), win, loss, win / Math.abs(loss), m,
                        1.96 * Math.sqrt(var / x.size())));
            }
        }
        return b.toString();
    }

    /** 单笔最大盈利分布：≥ 20% / 50% / 100% 的笔数、最大一笔，全部和"普涨 + 山寨强于 BTC"行情里买入的。 */
    static String bigWins(List<Sim> sims, Regimes regimes, int fromQ) {
        StringBuilder b = new StringBuilder("### 单笔大赚（每笔收益 %，按买入时行情）\n\n| 方案 | 范围 | 笔数 | ≥ 20% | ≥ 50% | ≥ 100% | 最大一笔 % | 强势行情合计 U |\n|---|---|---:|---:|---:|---:|---:|---:|\n");
        for (Sim s : sims) {
            for (boolean strongOnly : new boolean[]{false, true}) {
                List<Trade> ts = s.trades().stream().filter(t -> t.entryQ() >= fromQ)
                        .filter(t -> !strongOnly || t.regime() == 1 || t.regime() == 2).toList();
                b.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %d | %d | %.1f | %s |%n", s.name(), strongOnly ? "强势行情买入" : "全部",
                        ts.size(), ts.stream().filter(t -> t.pct() >= 20).count(), ts.stream().filter(t -> t.pct() >= 50).count(),
                        ts.stream().filter(t -> t.pct() >= 100).count(), ts.stream().mapToDouble(Trade::pct).max().orElse(Double.NaN),
                        strongOnly ? String.format(Locale.ROOT, "%,.0f", ts.stream().mapToDouble(Trade::pnl).sum()) : "—"));
            }
        }
        return b.toString();
    }

    /** 用户指定的山寨行情段（2026-11-02）：按买入时间落在段内的笔数、合计、每笔、最大一笔。段的起止见报告正文的数据确认。 */
    static final String[][] WINDOWS = {{"2023-10-15", "2024-03-31"}, {"2024-02-15", "2024-03-31"}, {"2024-11-01", "2024-12-31"},
            {"2025-04-20", "2025-05-31"}};

    static String windows(List<Sim> sims, MarketData data) {
        StringBuilder b = new StringBuilder("### 山寨行情段（按买入日期，U）\n\n| 方案 |");
        for (String[] w : WINDOWS) b.append(" ").append(w[0]).append(" ～ ").append(w[1]).append("（笔数 / 合计 / 每笔 / 最大一笔%） |");
        b.append("\n|---|").append("---:|".repeat(WINDOWS.length)).append('\n');
        for (Sim s : sims) {
            b.append("| ").append(s.name()).append(" |");
            for (String[] w : WINDOWS) {
                long a = LocalDate.parse(w[0]).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                long z = LocalDate.parse(w[1]).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                List<Trade> ts = s.trades().stream().filter(t -> data.time(t.entryQ()) >= a && data.time(t.entryQ()) < z).toList();
                double sum = ts.stream().mapToDouble(Trade::pnl).sum();
                b.append(String.format(Locale.ROOT, " %d / %,.0f / %+.2f / %.1f |", ts.size(), sum, ts.isEmpty() ? 0 : sum / ts.size(),
                        ts.stream().mapToDouble(Trade::pct).max().orElse(Double.NaN)));
            }
            b.append('\n');
        }
        return b.toString();
    }

    /**
     * 每周一行的山寨行情指标（只用当时已收盘的数据）：池内（当天标的池）30 日收益中位数、减 BTC 30 日收益、价格在 50 日 / 200 日均线
     * 上方的比例、ETH/BTC 相对其 50 日均线。用来确认山寨行情段的起止。日线收盘取每天最后一根 15m 收盘。
     */
    static String altMarket(MarketData data, int fromQ) {
        StringBuilder b = new StringBuilder("### 山寨行情指标（每周一 00:00 UTC）\n\n| 日期 | 池内 30 日收益中位 % | 减 BTC 30 日 % | 50 日线上方 % | 200 日线上方 % | ETH/BTC 比 50 日均线 % |\n|---|---:|---:|---:|---:|---:|\n");
        int day = MarketData.DAY;
        for (int q = fromQ - fromQ % day + day - 1; q < data.size; q += day) {
            long t = data.time(q) + Series.Q;
            if (java.time.Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).getDayOfWeek() != java.time.DayOfWeek.MONDAY) continue;
            List<Double> r30 = new ArrayList<>();
            int above50 = 0, above200 = 0, n50 = 0, n200 = 0;
            for (String sym : data.universe(q)) {
                Series x = data.get(sym);
                if (x == null || !x.has(q)) continue;
                double c = x.close[q];
                double c30 = close(x, q - 30 * day);
                if (c30 > 0) r30.add((c / c30 - 1) * 100);
                double m50 = sma(x, q, 50, day), m200 = sma(x, q, 200, day);
                if (!Double.isNaN(m50)) { n50++; if (c > m50) above50++; }
                if (!Double.isNaN(m200)) { n200++; if (c > m200) above200++; }
            }
            Series btc = data.get(MarketData.BTC), eth = data.get(MarketData.ETH);
            double b30 = btc == null || !btc.has(q) ? Double.NaN : (btc.close[q] / close(btc, q - 30 * day) - 1) * 100;
            double eb = Double.NaN;
            if (eth != null && btc != null && eth.has(q) && btc.has(q)) {
                double sum = 0;
                int k = 0;
                for (int i = 0; i < 50; i++) {
                    double e = close(eth, q - i * day), bb = close(btc, q - i * day);
                    if (e > 0 && bb > 0) { sum += e / bb; k++; }
                }
                if (k == 50) eb = (eth.close[q] / btc.close[q] / (sum / k) - 1) * 100;
            }
            java.util.Collections.sort(r30);
            double med = r30.isEmpty() ? Double.NaN : r30.get(r30.size() / 2);
            b.append(String.format(Locale.ROOT, "| %s | %+.1f | %+.1f | %.0f | %.0f | %+.1f |%n",
                    java.time.Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate(), med, med - b30,
                    n50 == 0 ? Double.NaN : above50 * 100.0 / n50, n200 == 0 ? Double.NaN : above200 * 100.0 / n200, eb));
        }
        return b.toString();
    }

    private static double close(Series x, int q) {
        return q < 0 || !x.has(q) ? Double.NaN : x.close[q];
    }

    private static double sma(Series x, int q, int days, int day) {
        double sum = 0;
        for (int i = 0; i < days; i++) {
            double c = close(x, q - i * day);
            if (!(c > 0)) return Double.NaN;
            sum += c;
        }
        return sum / days;
    }

    private static double protectFloor(StrategyParams params, double cost, double peak) {
        return com.trendpilot.monitor.ExitPolicy.protectionFloor(java.math.BigDecimal.valueOf(cost), java.math.BigDecimal.valueOf(peak),
                java.math.BigDecimal.ZERO, params.exit()).doubleValue();
    }

    private static double median(List<Double> x) {
        if (x.isEmpty()) return Double.NaN;
        List<Double> y = new ArrayList<>(x);
        java.util.Collections.sort(y);
        return y.size() % 2 == 1 ? y.get(y.size() / 2) : (y.get(y.size() / 2 - 1) + y.get(y.size() / 2)) / 2;
    }

    private static String describe(Candidate c) {
        List<String> p = new ArrayList<>();
        if (!c.overrides().isEmpty()) p.add(c.overrides().describe());
        c.config().forEach((k, v) -> p.add(k.replace("trendpilot.strategy.", "") + " = " + v));
        if (c.discoverEvery() != 4) p.add("每 " + c.discoverEvery() * 15 + " 分钟审核一次");
        if (c.sim().containsKey("momentum-top")) p.add("涨幅榜动量：滚动 " + c.sim().getOrDefault("momentum-hours", "24") + "h 涨幅前 " + c.sim().get("momentum-top") + " 名");
        if (c.sim().containsKey("stop-fixed")) p.add("固定止损 " + c.sim().get("stop-fixed") + "%");
        if (c.sim().containsKey("trail-atr")) p.add("跟踪止损 ATR×" + c.sim().get("trail-atr"));
        if (c.sim().containsKey("max-ret4h-pct")) p.add("买入前 4h 涨幅 ≥ " + c.sim().get("max-ret4h-pct") + "% 不开");
        if (c.sim().containsKey("stale-hours")) p.add("持有 " + c.sim().get("stale-hours") + "h 仍浮亏按 1h 收盘卖（替代 72h 等反弹）");
        if (c.sim().containsKey("runner-regimes")) p.add("宽跟踪只在行情 " + c.sim().get("runner-regimes") + "（1 普涨、2 山寨强于BTC）时生效");
        if (c.sim().containsKey("base")) p.add("对照基准 " + c.sim().get("base"));
        return String.join("；", p);
    }

    private static String label(ReleaseCheck.Verdict v) {
        return switch (v) {
            case PASS -> "**通过**";
            case PROMISING -> "有希望";
            case WORSE -> "更差";
        };
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static int q(MarketData data, LocalDate d) {
        return (int) ((d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - data.start) / Series.Q);
    }
}
