package com.craftstudio.imgutil.domain.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

public record Artifact(String filename, String mediaType, long bytes, String sha256,
                       double width, double height, String unit, String downloadPath, String objectName,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> assets) {
    public Artifact { assets = assets == null ? Map.of() : Map.copyOf(assets); }
    public Artifact(String filename, String mediaType, long bytes, String sha256, double width, double height,
                    String unit, String downloadPath, String objectName) {
        this(filename, mediaType, bytes, sha256, width, height, unit, downloadPath, objectName, Map.of());
    }
}
