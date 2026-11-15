package com.craftstudio.imgutil.domain.task;

public record Artifact(String filename, String mediaType, long bytes, String sha256,
                       double width, double height, String unit, String downloadPath, String objectName) { }
