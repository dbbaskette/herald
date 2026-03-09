package com.herald;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HeraldApplication {

    // TODO: When spring-ai-agent-utils is available, register the following tool beans:
    //  - SkillsTool
    //  - ShellTools
    //  - FileSystemTools
    //  - TodoWriteTool
    //  - AskUserQuestionTool
    //  - TaskTool

    public static void main(String[] args) {
        SpringApplication.run(HeraldApplication.class, args);
    }
}
