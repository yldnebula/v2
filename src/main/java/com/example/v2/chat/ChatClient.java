package com.example.v2.chat;

/**
 * 我们自定义的ChatClient接口，模仿Spring AI。
 */
public interface ChatClient {
    ChatResponse call(Prompt prompt);
}
