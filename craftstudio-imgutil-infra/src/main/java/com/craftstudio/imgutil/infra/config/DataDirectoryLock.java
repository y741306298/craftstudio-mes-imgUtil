package com.craftstudio.imgutil.infra.config;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;

/** File-backed queue ownership is exclusive. Cluster deployments must use a shared queue/repository. */
@Component
public class DataDirectoryLock {
    private final FileChannel channel;
    private final FileLock lock;
    public DataDirectoryLock(GraphicsProperties properties) throws Exception {
        Path root = Path.of(properties.dataDirectory()).toAbsolutePath(); Files.createDirectories(root);
        channel = FileChannel.open(root.resolve(".instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("数据目录已被另一个图形服务实例使用");
        } catch (Exception e) { channel.close(); throw e; }
    }
    @PreDestroy public void close() throws Exception { lock.release(); channel.close(); }
}
