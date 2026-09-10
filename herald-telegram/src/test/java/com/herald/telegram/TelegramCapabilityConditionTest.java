package com.herald.telegram;

import com.herald.config.HeraldConfig;
import com.pengrad.telegrambot.TelegramBot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;

class TelegramCapabilityConditionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TelegramBotConfig.class, TelegramSender.class, TelegramPoller.class,
                    TelegramQuestionHandler.class, TelegramMcpElicitationHandler.class);

    @ParameterizedTest @CsvSource({"'', ''", "'   ',42", "fixture, '   '", "fixture, ''", "'', 42"})
    void incompleteCredentialsCreateNoTelegramBeans(String token, String chat) {
        runner.withPropertyValues("herald.telegram.bot-token=" + token, "herald.telegram.allowed-chat-id=" + chat)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(TelegramBot.class)
                            .doesNotHaveBean(TelegramSender.class).doesNotHaveBean(TelegramPoller.class)
                            .doesNotHaveBean(TelegramQuestionHandler.class).doesNotHaveBean(TelegramMcpElicitationHandler.class);
                });
    }
    @Test void taskModeDoesNotConstructTelegramEvenWithCredentials() {
        runner.withPropertyValues("agents=fixture.md", "herald.telegram.bot-token=fixture", "herald.telegram.allowed-chat-id=42")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TelegramBot.class).doesNotHaveBean(TelegramPoller.class));
    }
    @Test void completeAssistantCredentialsCreateClientWithoutNetworkProbe() {
        new ApplicationContextRunner().withUserConfiguration(TelegramBotConfig.class, Config.class)
                .withPropertyValues("herald.telegram.bot-token=123:fixture", "herald.telegram.allowed-chat-id=42")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TelegramBot.class));
    }
    @Configuration(proxyBeanMethods = false) static class Config {
        @Bean HeraldConfig heraldConfig() { return new HeraldConfig(null, new HeraldConfig.Telegram("123:fixture", "42"),
                null, null, null, null, null, null, null, null); }
    }
}
