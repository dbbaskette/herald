package com.herald.telegram;

import com.herald.config.HeraldConfig;
import com.pengrad.telegrambot.TelegramBot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnProperty("herald.telegram.bot-token")
@EnableScheduling
class TelegramBotConfig {

    @Bean
    TelegramBot telegramBot(HeraldConfig config) {
        return new TelegramBot(config.telegram().botToken());
    }
}
