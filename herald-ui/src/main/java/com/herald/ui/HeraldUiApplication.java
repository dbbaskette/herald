package com.herald.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = DataJdbcRepositoriesAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class HeraldUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeraldUiApplication.class, args);
    }
}
