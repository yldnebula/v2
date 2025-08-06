package com.example.v2.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 我们自定义的AssistantMessage DTO，模仿Spring AI，用于解析LLM的回复。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistantMessage(
    String content,
    @JsonProperty("tool_calls") List<ToolCall> toolCalls
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(String id, String type, Function function) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Function(String name, String arguments) {}
}
