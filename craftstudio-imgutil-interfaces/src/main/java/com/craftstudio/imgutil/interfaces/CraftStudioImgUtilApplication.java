package com.craftstudio.imgutil.interfaces;

import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.craftstudio.imgutil")
@EnableConfigurationProperties(GraphicsProperties.class)
@EnableScheduling
public class CraftStudioImgUtilApplication {
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(CraftStudioImgUtilApplication.class, args);
    }
}
