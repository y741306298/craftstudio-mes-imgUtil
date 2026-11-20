package com.craftstudio.imgutil.domain.graphics;

import java.util.Map;

public record RenderResult(String mediaType, String extension, double width, double height, String unit,
                           Map<String, String> assets, boolean publishToOss) {
    public RenderResult { assets = assets == null ? Map.of() : Map.copyOf(assets); }
    public RenderResult(String mediaType, String extension, double width, double height, String unit,
                        Map<String, String> assets) {
        this(mediaType, extension, width, height, unit, assets, true);
    }
    public RenderResult(String mediaType, String extension, double width, double height, String unit) {
        this(mediaType, extension, width, height, unit, Map.of());
    }
}
