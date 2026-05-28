package com.herald.telegram;

import java.util.concurrent.TimeUnit;

import com.herald.config.HeraldConfig;
import com.pengrad.telegrambot.TelegramBot;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty("herald.telegram.bot-token")
public class TelegramBotConfig {

    /**
     * Dedicated OkHttp client for {@link TelegramBot}. Exposed as a bean so the
     * poller can call {@code connectionPool().evictAll()} when consecutive
     * polls fail — that's how we recover from stale half-open TLS sockets
     * after a network blip (#TBD).
     *
     * <p>Timeouts match the pengrad library defaults. {@code pingInterval} is
     * new: HTTP/2 PING frames every 30s tell us a socket is dead well before
     * the connection-pool's 5-minute idle eviction kicks in.</p>
     */
    @Bean
    public OkHttpClient telegramOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(75, TimeUnit.SECONDS)
                .readTimeout(75, TimeUnit.SECONDS)
                .writeTimeout(75, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Bean
    public TelegramBot telegramBot(HeraldConfig config, OkHttpClient telegramOkHttpClient) {
        return new TelegramBot.Builder(config.telegram().botToken())
                .okHttpClient(telegramOkHttpClient)
                .build();
    }
}
