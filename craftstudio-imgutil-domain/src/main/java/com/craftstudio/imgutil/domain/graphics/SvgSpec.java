package com.craftstudio.imgutil.domain.graphics;

import java.util.List;
import java.util.Map;

/** SVG coordinates use MES millimetre conventions; never fetch referenced images. */
public record SvgSpec(Kind kind, String source, double width, double height, Insets margins,
                      String sourceImage, boolean requirePlt, boolean normalizeRects,
                      List<SvgMark> marks, List<String> removeGroupIds, Map<String, String> imageReplacements) {
    public enum Kind { MASK, EDIT }
    public SvgSpec {
        margins = margins == null ? Insets.ZERO : margins;
        marks = marks == null ? List.of() : List.copyOf(marks);
        removeGroupIds = removeGroupIds == null ? List.of() : List.copyOf(removeGroupIds);
        imageReplacements = imageReplacements == null ? Map.of() : Map.copyOf(imageReplacements);
    }
}
