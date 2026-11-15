package com.craftstudio.imgutil.domain.graphics;

/** An idempotent job contains exactly one raster or SVG operation. */
public record GraphicsRequest(String requestId, RasterSpec raster, SvgSpec svg) {
    public void validate() {
        if (requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) {
            throw new IllegalArgumentException("requestId 必须为 1–80 位字母、数字、下划线或连字符");
        }
        if ((raster == null) == (svg == null)) throw new IllegalArgumentException("raster 和 svg 必须且只能提供一个");
    }
}
