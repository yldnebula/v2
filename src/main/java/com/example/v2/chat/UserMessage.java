package com.example.v2.chat;

/**
 * 代表一个用户角色的消息。
 */
public record UserMessage(String role, String content) {
    public UserMessage(String content) {
        this("user", content);
    }
}
