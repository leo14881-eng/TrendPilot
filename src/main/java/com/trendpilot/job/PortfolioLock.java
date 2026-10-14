package com.trendpilot.job;

import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

import com.trendpilot.notify.Notifier;

/**
 * 组合锁：Discovery 与 Monitor 写账本时互斥。单进程单实例，用进程内锁即可。
 * 行情抓取和计算都在锁外完成，锁内只做"读组合 → 判定 → 写事件"。
 * 锁内产生的通知先写 Outbox，解锁后再发 Telegram，网络慢不会拖住另一个任务。
 */
@Component
public class PortfolioLock {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Notifier notifier;

    public PortfolioLock(Notifier notifier) {
        this.notifier = notifier;
    }

    public void run(Runnable critical) {
        boolean outer = notifier.deferOnThisThread();
        lock.lock();
        try {
            critical.run();
        } finally {
            lock.unlock();
            if (outer) notifier.flushDeferred();
        }
    }
}
