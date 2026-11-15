package com.craftstudio.imgutil.domain.graphics;

public record Insets(double left, double top, double right, double bottom) {
    public static final Insets ZERO = new Insets(0, 0, 0, 0);
    public void validate() {
        for (double value : new double[]{left, top, right, bottom}) {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("边距必须为非负有限数");
        }
    }
}
