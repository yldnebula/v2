package com.example.v2.chat;

import java.util.List;

/**
 * 我们自定义的Prompt DTO，模仿Spring AI。
 * @param messages 对话消息列表，可以是多种类型的消息对象。
 * @param options 本次调用的选项。
 */
public record Prompt(List<Object> messages, ChatOptions options) {}