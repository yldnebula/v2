package com.example.v2.state;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话状态的数据模型 (DTO)。
 * @param conversationId 会话ID。
 * @param intentName 当前正在处理的意图名称。
 * @param requiredSlots 完成此意图需要的所有槽位名称。
 * @param collectedSlots 当前已收集到的槽位和对应的值。
 * @param status 当前对话的状态 (例如：正在收集信息、等待用户确认)。
 */
public record DialogueState(
    String conversationId,
    String intentName,
    Set<String> requiredSlots,
    Map<String, Object> collectedSlots,
    Status status
) {
    public enum Status {
        GATHERING_INFO,      // 正在收集中
        CONFIRMATION_PENDING, // 等待确认中
        FINISHED,            // 已完成
        CANCELLED            // 已取消
    }
}
