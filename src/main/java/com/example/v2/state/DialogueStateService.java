package com.example.v2.state;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话状态服务 (机器人的短期记忆)。
 * 负责在多轮对话中追踪一个特定任务的进展。
 * 在生产环境中，这应该被替换为Redis、数据库或其他分布式缓存方案。
 */
@Service
public class DialogueStateService {

    // 使用线程安全的ConcurrentHashMap来存储不同用户的对话状态
    // Key: conversationId (可以是 userId 或其他唯一会话标识)
    // Value: 该会话的当前状态
    private final Map<String, DialogueState> stateCache = new ConcurrentHashMap<>();

    public void saveState(String conversationId, DialogueState state) {
        System.out.println("--- [状态服务] 保存状态 for " + conversationId + ": " + state + " ---");
        stateCache.put(conversationId, state);
    }

    public Optional<DialogueState> getState(String conversationId) {
        DialogueState state = stateCache.get(conversationId);
        System.out.println("--- [状态服务] 获取状态 for " + conversationId + ": " + state + " ---");
        return Optional.ofNullable(state);
    }

    public void clearState(String conversationId) {
        System.out.println("--- [状态服务] 清除状态 for " + conversationId + " ---");
        stateCache.remove(conversationId);
    }
}
