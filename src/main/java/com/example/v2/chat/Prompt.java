package com.example.v2.chat;

import java.util.List;
import java.util.Map;

/**
 * 我们自定义的Prompt DTO，模仿Spring AI。
 */
public record Prompt(List<Map<String, Object>> messages, ChatOptions options) {}
