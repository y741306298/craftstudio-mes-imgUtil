package com.craftstudio.imgutil.interfaces.config;

import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionApiKeyGuard {
    public ProductionApiKeyGuard(GraphicsProperties properties) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) throw new IllegalArgumentException("生产环境必须配置非空 GRAPHICS_API_KEY");
    }
}
