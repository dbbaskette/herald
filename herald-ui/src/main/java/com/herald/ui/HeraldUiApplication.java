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
        if (java.util.Arrays.asList(args).contains("--validate-config")) {
            com.herald.ui.security.ConsoleConfigValidator.main(java.util.Arrays.stream(args)
                    .filter(arg -> !arg.equals("--validate-config")).toArray(String[]::new));
            return;
        }
        SpringApplication.run(HeraldUiApplication.class, args);
    }
}
