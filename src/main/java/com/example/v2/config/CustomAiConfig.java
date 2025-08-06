package com.example.v2.config;

import com.example.v2.chat.ChatClient;
import com.example.v2.chat.CustomOpenAiChatClient;
import com.example.v2.chat.HttpHeaderProvider;
import com.example.v2.metadata.ToolMetadataService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomAiConfig {

    @Bean
    public ChatClient customChatClient(
        @Value("${openai.api.key}") String apiKey,
        HttpHeaderProvider headerProvider,
        ToolMetadataService metadataService // 注入元数据服务
    ) {
        return new CustomOpenAiChatClient(apiKey, headerProvider, metadataService);
    }
}
