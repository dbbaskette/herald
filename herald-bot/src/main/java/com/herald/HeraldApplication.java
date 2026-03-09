package com.herald;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class HeraldApplication {

    // TODO: When spring-ai-agent-utils stubs are replaced, migrate to canonical versions:
    //  - SkillsTool
    //  - ShellTools
    //  - FileSystemTools (currently stub)
    //  - TodoWriteTool (currently stub)
    //  - AskUserQuestionTool
    // TaskTool is now wired via HeraldAgentConfig

    public static void main(String[] args) {
        SpringApplication.run(HeraldApplication.class, args);
    }
}
