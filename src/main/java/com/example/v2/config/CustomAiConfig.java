package com.example.v2.config;

import com.example.v2.chat.ChatClient;
import com.example.v2.chat.CustomOpenAiChatClient;
import com.example.v2.chat.HttpHeaderProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomAiConfig {

    @Bean
    public ChatClient customChatClient(
        @Value("${openai.api.key}") String apiKey,
        ApplicationContext context,
        HttpHeaderProvider headerProvider // 注入请求头提供者
    ) {
        return new CustomOpenAiChatClient(apiKey, context, headerProvider);
    }
}