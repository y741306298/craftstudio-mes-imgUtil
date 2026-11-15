package com.craftstudio.imgutil.domain.graphics;

import java.util.Map;

/** Versioned, allowlisted operations preserving MES production rendering rules. */
public record MesSpec(String operation, Map<String, Object> parameters) {
    public MesSpec { parameters = parameters == null ? Map.of() : Map.copyOf(parameters); }
}
