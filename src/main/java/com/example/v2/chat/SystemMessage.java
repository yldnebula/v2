package com.example.v2.chat;

/**
 * 代表一个系统角色的消息。
 */
public record SystemMessage(String role, String content) {
    public SystemMessage(String content) {
        this("system", content);
    }
}
