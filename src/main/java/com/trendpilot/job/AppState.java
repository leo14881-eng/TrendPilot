package com.trendpilot.job;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AppState {

    private final JdbcClient jdbc;

    public AppState(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.sql("SELECT value FROM app_state WHERE key = :k").param("k", key).query(String.class).optional();
    }

    public void put(String key, String value) {
        jdbc.sql("INSERT OR REPLACE INTO app_state(key, value) VALUES (:k, :v)").param("k", key).param("v", value)
                .update();
    }
}
