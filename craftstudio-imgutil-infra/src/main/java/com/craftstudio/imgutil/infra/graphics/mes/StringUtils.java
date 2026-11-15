package com.craftstudio.imgutil.infra.graphics.mes;

final class StringUtils {
    static boolean isNotEmpty(String value) { return value != null && !value.isEmpty(); }
    static boolean isBlank(String value) { return value == null || value.isBlank(); }
    static boolean isNotBlank(String value) { return !isBlank(value); }
}
