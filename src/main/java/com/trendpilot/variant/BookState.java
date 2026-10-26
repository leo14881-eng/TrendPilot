package com.trendpilot.variant;

import java.util.Optional;

import com.trendpilot.job.AppState;

/**
 * 某个影子变体自己的风控状态：把 RiskRecovery 用的 "risk:xxx" 键改写成 "risk:&lt;变体&gt;:xxx"，
 * 存在同一张 app_state 表里，和 V2 的 "risk:xxx" 互不影响。
 */
final class BookState extends AppState {

    private final AppState delegate;
    private final String book;

    BookState(AppState delegate, String book) {
        super(null);
        this.delegate = delegate;
        this.book = book;
    }

    static String key(String book, String key) {
        return key.startsWith("risk:") ? "risk:" + book + ":" + key.substring(5) : "risk:" + book + ":" + key;
    }

    @Override
    public Optional<String> get(String key) {
        return delegate.get(key(book, key));
    }

    @Override
    public void put(String key, String value) {
        delegate.put(key(book, key), value);
    }
}
