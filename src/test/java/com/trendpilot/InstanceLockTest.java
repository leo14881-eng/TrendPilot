package com.trendpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceLockTest {

    @TempDir
    Path dir;

    @Test
    void secondHolderIsRejected() throws Exception {
        InstanceLock.acquire(dir);
        try {
            assertThat(Files.readString(dir.resolve(".instance.lock"))).contains("/pid ");
            // 别的进程再锁同一个文件拿不到（同一 JVM 里表现为 OverlappingFileLockException）
            try (FileChannel other = FileChannel.open(dir.resolve(".instance.lock"), StandardOpenOption.WRITE)) {
                assertThatThrownBy(other::tryLock).isInstanceOf(OverlappingFileLockException.class);
            }
            InstanceLock.acquire(dir); // 同一进程重复调用不报错
        } finally {
            InstanceLock.release();
        }
        try (FileChannel again = FileChannel.open(dir.resolve(".instance.lock"), StandardOpenOption.WRITE)) {
            assertThat(again.tryLock()).isNotNull();
        }
    }
}
