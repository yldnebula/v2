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
    List<ChatMessage> chatHistory // 记录组件名为 chatHistory
) {
    public enum Status {
        GATHERING_INFO,      // 正在收集中
        CONFIRMATION_PENDING, // 等待确认中
        FINISHED,            // 已完成
        CANCELLED            // 已取消
    }

    public record OriginatingIntent(String intentName, Map<String, Object> arguments) {}

    /**
     * 这是一个对记录的规范构造器的显式声明。
     * **修正点**: 将参数名 history 改为 chatHistory，以匹配记录组件的名称，这是Java record规范的要求。
     * 我们在这里显式声明它，是为了确保传入的chatHistory列表是可变的，通过创建一个ArrayList副本。
     */
    public DialogueState(String conversationId, String intentName, Set<String> requiredSlots, Map<String, Object> collectedSlots, Status status, OriginatingIntent originatingIntent, List<ChatMessage> chatHistory) {
        this.conversationId = conversationId;
        this.intentName = intentName;
        this.requiredSlots = requiredSlots;
        this.collectedSlots = collectedSlots;
        this.status = status;
        this.originatingIntent = originatingIntent;
        this.chatHistory = new java.util.ArrayList<>(chatHistory); // 创建一个可变副本
    }

    /**
     * 提供一个不带父任务但带历史的便捷构造器。
     * **修正点**: 将参数名 history 改为 chatHistory，以正确地委托给上面的规范构造器。
     */
    public DialogueState(String conversationId, String intentName, Set<String> requiredSlots, Map<String, Object> collectedSlots, Status status, List<ChatMessage> chatHistory) {
        this(conversationId, intentName, requiredSlots, collectedSlots, status, null, chatHistory);
    }
}