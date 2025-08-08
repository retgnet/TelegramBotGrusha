package com.maximov.grusha.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Data
public class BotConfig {
    @Value("${bot.token}")
    private String botToken;
    @Value("${bot.name}")
    private String botName;

}
