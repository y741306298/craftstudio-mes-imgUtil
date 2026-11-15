package com.craftstudio.imgutil.domain.graphics;

/** Coordinates are top-left, before optional rotation around the mark centre. */
public record SvgMark(Kind kind, String id, double x, double y, double width, double height,
                      String image, boolean requirePlt, double rotationDegrees) {
    public enum Kind { RECT, CIRCLE, CROSS }
}
