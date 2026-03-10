package com.herald.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HeraldUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeraldUiApplication.class, args);
    }
}
