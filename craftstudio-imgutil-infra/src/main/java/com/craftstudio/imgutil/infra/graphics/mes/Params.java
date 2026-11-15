package com.craftstudio.imgutil.infra.graphics.mes;

import java.util.*;

public class Params {
    final Map<String, Object> values;
    public Params(Map<String, Object> values) { this.values = values; }
    public double number(String key, double fallback) {
        Object value=values.get(key); double result=value == null ? fallback : Double.parseDouble(value.toString());
        if (!Double.isFinite(result) || Math.abs(result)>10000000) throw new IllegalArgumentException("参数超出范围: "+key);
        return result;
    }
    public int integer(String key, int fallback) {
        Object value=values.get(key); return value == null ? fallback : Integer.parseInt(value.toString());
    }
    public boolean bool(String key, boolean fallback) { Object v=values.get(key);return v == null ? fallback : Boolean.parseBoolean(v.toString()); }
    public String text(String key) { return Objects.toString(values.get(key), ""); }
    public List<String> strings(String key) { Object v=values.get(key); return v == null ? List.of() : ((List<?>)v).stream().map(Object::toString).toList(); }
    public List<Integer> integers(String key) { Object v=values.get(key); return v == null ? List.of(java.awt.Color.BLACK.getRGB()) : ((List<?>)v).stream().map(x->Integer.parseInt(x.toString())).toList(); }
}
