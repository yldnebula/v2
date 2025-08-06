package com.example.v2.state;

/**
 * 对话消息的数据模型 (DTO)。
 * 用于标准地表示一次对话中的单条消息。
 * @param role 角色 (例如 "user", "assistant", "system")。
 * @param content 消息的具体内容。
 */
public record ChatMessage(String role, String content) {}
