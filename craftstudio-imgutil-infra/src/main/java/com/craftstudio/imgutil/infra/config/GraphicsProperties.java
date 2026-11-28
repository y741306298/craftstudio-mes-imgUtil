package com.craftstudio.imgutil.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("graphics")
// queueCapacity, maxDimension and maxPixels are retained only for legacy configuration binding.
public record GraphicsProperties(String apiKey, String dataDirectory, int workers, int queueCapacity,
                                  int taskTimeoutSeconds, int retentionHours, int maxDimension,
                                  long maxPixels, int maxDpi, int maxRequestBytes, int maxArtifactBytes,
                                  int maxSvgNodes, int maxSvgDepth, long maxStorageBytes, Oss oss) {
    public GraphicsProperties {
        if (dataDirectory == null || workers < 1 || workers > 8
                || taskTimeoutSeconds < 1 || retentionHours < 1
                || maxDpi < 1 || maxRequestBytes < 1 || maxArtifactBytes < 1 || maxSvgNodes < 1 || maxSvgDepth < 1
                || maxStorageBytes < maxArtifactBytes) throw new IllegalArgumentException("图形服务资源配置无效");
        if (oss != null && oss.enabled() && (blank(oss.endpoint()) || blank(oss.bucket())
                || blank(oss.accessKeyId()) || blank(oss.accessKeySecret()))) throw new IllegalArgumentException("OSS 配置不完整");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public record Oss(boolean enabled, String endpoint, String bucket, String accessKeyId, String accessKeySecret, String prefix) { }
}
