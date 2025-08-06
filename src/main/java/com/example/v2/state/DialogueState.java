package com.example.v2.state;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话状态的数据模型 (DTO) - 增加了对话历史记录。
 * @param conversationId 会话ID。
 * @param intentName 当前正在处理的意图名称。
 * @param requiredSlots 完成此意图需要的所有槽位名称。
 * @param collectedSlots 当前已收集到的槽位和对应的值。
 * @param status 当前对话的状态。
 * @param originatingIntent 触发当前任务的“父任务”。
 * @param chatHistory 本次任务的对话历史记录，用于联合上下文。
 */
public record DialogueState(
    String conversationId,
    String intentName,
    Set<String> requiredSlots,
    Map<String, Object> collectedSlots,
    Status status,
    OriginatingIntent originatingIntent,
    List<ChatMessage> chatHistory // 新增字段：对话历史
) {
    public enum Status {
        GATHERING_INFO,      // 正在收集中
        CONFIRMATION_PENDING, // 等待确认中
        FINISHED,            // 已完成
        CANCELLED            // 已取消
    }

    public record OriginatingIntent(String intentName, Map<String, Object> arguments) {}

    // 提供一个包含父任务和历史的构造器
    public DialogueState(String conversationId, String intentName, Set<String> requiredSlots, Map<String, Object> collectedSlots, Status status, OriginatingIntent originatingIntent, List<ChatMessage> history) {
        this.conversationId = conversationId;
        this.intentName = intentName;
        this.requiredSlots = requiredSlots;
        this.collectedSlots = collectedSlots;
        this.status = status;
        this.originatingIntent = originatingIntent;
        this.chatHistory = new java.util.ArrayList<>(history); // 创建一个可变副本
    }

    // 提供一个不带父任务但带历史的构造器
    public DialogueState(String conversationId, String intentName, Set<String> requiredSlots, Map<String, Object> collectedSlots, Status status, List<ChatMessage> history) {
        this(conversationId, intentName, requiredSlots, collectedSlots, status, null, history);
    }
}
