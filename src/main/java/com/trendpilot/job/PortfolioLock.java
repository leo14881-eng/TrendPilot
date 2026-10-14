package com.trendpilot.job;

import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

/**
 * 组合锁：Discovery 与 Monitor 写账本时互斥。单进程单实例，用进程内锁即可。
 * 行情抓取和计算都在锁外完成，锁内只做"读组合 → 判定 → 写事件"。
 */
@Component
public class PortfolioLock {

    private final ReentrantLock lock = new ReentrantLock(true);

    public void run(Runnable critical) {
        lock.lock();
        try {
            critical.run();
        } finally {
            lock.unlock();
        }
    }
}
