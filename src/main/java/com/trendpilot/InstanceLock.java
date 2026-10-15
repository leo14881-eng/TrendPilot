package com.trendpilot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * 单实例保护：启动时对 data/.instance.lock 加操作系统文件锁，拿不到就立刻退出，
 * 防止同一台电脑上（或两个进程指向同一个数据目录时）同时跑两份服务、重复记账、重复发 Telegram。
 * 进程退出（包括被强杀）时操作系统自动释放锁，不会留下"死锁文件"。
 * <p>
 * 注意：这只管同一个数据目录。两台电脑各有自己的 data 目录，各自都能启动；
 * 目前是影子模式不下单，最坏是两份账本和两份 Telegram（启动消息里带机器名可以看出来）。
 * 跨机器的保护见 lease.ActiveLease。
 */
public final class InstanceLock {

    static final long LOCK_POSITION = Long.MAX_VALUE - 1;

    private static FileChannel channel;
    private static FileLock lock;

    private InstanceLock() {
    }

    /** 本机标识：机器名 + 进程号，写进启动通知，方便看出是哪台机器在跑。 */
    public static String instanceId() {
        return hostName() + "/pid " + ProcessHandle.current().pid();
    }

    public static String hostName() {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                host = "unknown";
            }
        }
        return host;
    }

    /** 加锁；已被别的进程持有时抛 IllegalStateException（启动失败）。同一进程重复调用无副作用。 */
    public static synchronized void acquire(Path dataDir) {
        if (lock != null && lock.isValid()) return;
        Path file = dataDir.resolve(".instance.lock");
        try {
            FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            // 只锁文件末尾之外的一个字节：Windows 的文件锁是强制锁，锁整个文件会让别的程序（和测试）读不了里面的机器名
            FileLock l = ch.tryLock(LOCK_POSITION, 1, false);
            if (l == null) {
                ch.close();
                throw new IllegalStateException("另一个 TrendPilot 已在使用数据目录 " + dataDir.toAbsolutePath()
                        + "，本进程退出，避免重复记账和重复通知");
            }
            ch.truncate(0);
            ch.write(ByteBuffer.wrap((instanceId() + " started " + Instant.now() + "\n").getBytes(StandardCharsets.UTF_8)));
            channel = ch;
            lock = l;
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建实例锁 " + file, e);
        }
    }

    /** 测试用：释放锁。 */
    static synchronized void release() throws IOException {
        if (lock != null) lock.release();
        if (channel != null) channel.close();
        lock = null;
        channel = null;
    }
}
