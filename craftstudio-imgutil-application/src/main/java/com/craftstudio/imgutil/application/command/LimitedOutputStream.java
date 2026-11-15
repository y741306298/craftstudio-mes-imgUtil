package com.craftstudio.imgutil.application.command;

import java.io.*;

final class LimitedOutputStream extends FilterOutputStream {
    private final long maximum;
    private long count;
    LimitedOutputStream(OutputStream target, long maximum) { super(target); this.maximum = maximum; }
    private void check(int bytes) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("任务已取消");
        if (bytes > maximum - count) throw new IOException("生成文件超过大小上限");
        count += bytes;
    }
    @Override public void write(int value) throws IOException { check(1); out.write(value); }
    @Override public void write(byte[] bytes, int offset, int length) throws IOException { check(length); out.write(bytes, offset, length); }
}
