package com.trendpilot.lpshadow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把每分钟的快照交给各池子的 {@link LpBook}，并把状态写回 lp_state。池子之间互不影响：一个池子出错只记 warn。
 * 重启后从 lp_state 恢复状态，从 lp_pool_snapshot 取最近一条快照作为"上一分钟"（中间停机的那段自然记为 gap）。
 */
final class LpEngine {

    private static final Logger log = LoggerFactory.getLogger(LpEngine.class);
    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final LpStore store;
    private final long rebalanceGas;
    private final Map<String, LpBook> books = new LinkedHashMap<>();
    private final Map<String, Integer> errors = new HashMap<>();

    LpEngine(LpStore store) {
        this(store, LpBook.DEFAULT_REBALANCE_GAS);
    }

    LpEngine(LpStore store, long rebalanceGas) {
        this.store = store;
        this.rebalanceGas = rebalanceGas;
    }

    Map<String, LpBook> books() {
        return books;
    }

    void onSnapshots(Map<String, PoolInfo> active, List<Snapshot> rows) {
        for (Snapshot s : rows) {
            PoolInfo info = active.get(s.pool());
            if (info == null) continue;
            try {
                LpBook book = books.computeIfAbsent(s.pool(), k -> load(info, s.ts()));
                book.onSnapshot(s);
                save(book, s.ts());
                errors.remove(s.pool());
            } catch (RuntimeException e) {
                int n = errors.merge(s.pool(), 1, Integer::sum);
                if (n == 1 || n % 60 == 0) log.warn("LP 影子盘：池子 {} 计算出错（连续 {} 次），本分钟不动作：{}", s.pool(), n, e.toString());
                books.remove(s.pool());   // 下一分钟从数据库重新加载
            }
        }
    }

    private LpBook load(PoolInfo info, long ts) {
        Map<String, PositionState> restored = new HashMap<>();
        store.states(info.key()).forEach((name, json) -> {
            try {
                restored.put(name, JSON.readValue(json, PositionState.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("状态无法解析：" + name, e);
            }
        });
        Snapshot prev = store.lastSnapshotBefore(info.key(), ts).orElse(null);
        return new LpBook(info, Strategy.forPool(info.key()), restored, prev, ledger(), rebalanceGas);
    }

    private void save(LpBook book, long ts) {
        List<String[]> rows = new ArrayList<>();
        for (PositionState st : book.snapshotStates()) {
            if (!st.started) continue;
            try {
                rows.add(new String[] {st.strategy, st.pool, JSON.writeValueAsString(st)});
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        store.saveStates(rows, ts);
    }

    private LpBook.Ledger ledger() {
        return new LpBook.Ledger() {
            @Override
            public long openPosition(String strategy, String pool, int tickLower, int tickUpper, double liquidity, long ts) {
                return store.openPosition(strategy, pool, tickLower, tickUpper, liquidity, ts);
            }

            @Override
            public void closePosition(long id, long ts) {
                store.closePosition(id, ts);
            }

            @Override
            public void event(long ts, String strategy, String type, Map<String, Object> detail) {
                try {
                    store.event(ts, strategy, type, JSON.writeValueAsString(detail));
                } catch (JsonProcessingException e) {
                    store.event(ts, strategy, type, null);
                }
            }
        };
    }
}
