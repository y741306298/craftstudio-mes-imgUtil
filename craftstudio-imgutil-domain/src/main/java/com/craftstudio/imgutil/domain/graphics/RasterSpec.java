package com.craftstudio.imgutil.domain.graphics;

import java.util.List;

/** All lengths are millimetres; font size is physical em height. Rotation is clockwise in degrees. */
public record RasterSpec(Kind kind, double widthMm, double heightMm, int dpi,
                         String foreground, String background, double strokeMm,
                         double fontSizeMm, String fontFamily, boolean bold, List<String> lines,
                         double stripeMm, boolean verticalStripes, double rotationDegrees,
                         Insets margins, double dashedInsetMm) {
    public static final String DEFAULT_LABEL_FONT_FAMILY = "Source Han Sans SC VF";

    public enum Kind { SOLID, BORDER, LABEL, STRIPES, CIRCLE, CROSS }
    public RasterSpec {
        foreground = foreground == null ? "#000000" : foreground;
        background = background == null ? "transparent" : background;
        fontFamily = fontFamily == null ? DEFAULT_LABEL_FONT_FAMILY : fontFamily;
        lines = lines == null ? List.of() : List.copyOf(lines);
        margins = margins == null ? Insets.ZERO : margins;
    }
}
