package com.example.v2.state;

import java.util.Map;
import java.util.Set;

/**
 * 对话状态的数据模型 (DTO)。
 * @param conversationId 会话ID。
 * @param intentName 当前正在处理的意图名称。
 * @param requiredSlots 完成此意图需要的所有槽位名称。
 * @param collectedSlots 当前已收集到的槽位和对应的值。
 * @param status 当前对话的状态。
 * @param originatingIntent 触发当前任务的“父任务”，用于在当前任务完成后回归主线。
 */
public record DialogueState(
    String conversationId,
    String intentName,
    Set<String> requiredSlots,
    Map<String, Object> collectedSlots,
    Status status,
    OriginatingIntent originatingIntent
) {
    public enum Status {
        GATHERING_INFO,      // 正在收集中
        CONFIRMATION_PENDING, // 等待确认中
        FINISHED,            // 已完成
        CANCELLED            // 已取消
    }

    /**
     * 记录原始意图的数据模型。
     */
    public record OriginatingIntent(String intentName, Map<String, Object> arguments) {}

    // 为了方便，提供一个不带父任务的构造器
    public DialogueState(String conversationId, String intentName, Set<String> requiredSlots, Map<String, Object> collectedSlots, Status status) {
        this(conversationId, intentName, requiredSlots, collectedSlots, status, null);
    }
}