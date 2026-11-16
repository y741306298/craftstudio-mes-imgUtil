package com.craftstudio.imgutil.infra.graphics;

import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import org.springframework.stereotype.Component;

@Component
public class RenderLimits {
    public RenderLimits(GraphicsProperties properties) { }
    public void checkDimensions(long width, long height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("画布宽高必须大于零");
    }
    public static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " 必须是有限数");
    }
    public static void positive(double value, String name) {
        finite(value, name);
        if (value <= 0) throw new IllegalArgumentException(name + " 必须大于 0");
    }
    public static void checkpoint() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("任务已取消或超时");
    }
}
