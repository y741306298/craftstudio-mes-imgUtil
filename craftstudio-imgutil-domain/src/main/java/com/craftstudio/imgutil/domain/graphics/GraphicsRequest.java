package com.craftstudio.imgutil.domain.graphics;

/** An idempotent job contains exactly one raster or SVG operation. */
public record GraphicsRequest(String requestId, RasterSpec raster, SvgSpec svg, MesSpec mes) {
    public GraphicsRequest(String requestId, RasterSpec raster, SvgSpec svg) { this(requestId, raster, svg, null); }
    public void validate() {
        if (requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) {
            throw new IllegalArgumentException("requestId 必须为 1–80 位字母、数字、下划线或连字符");
        }
        if ((raster == null ? 0 : 1) + (svg == null ? 0 : 1) + (mes == null ? 0 : 1) != 1) throw new IllegalArgumentException("raster、svg、mes 必须且只能提供一个");
    }
}
