package com.example.v2.service;

import com.example.v2.chat.ChatClient;
import com.example.v2.chat.ChatOptions;
import com.example.v2.chat.Prompt;
import com.example.v2.metadata.ToolMetadataService;
import com.example.v2.state.DialogueState;
import com.example.v2.state.DialogueStateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 对话流服务 (LLM专家) - 具备多轮槽位填充能力
 */
@Service
public class DialogueFlowService {

    @Autowired private ChatClient chatClient;
    @Autowired private ApplicationContext context;
    @Autowired private DialogueStateService stateService;
    @Autowired private ToolMetadataService metadataService;
    private final ObjectMapper mapper = new ObjectMapper();

    public record DialogueResponse(String reply, boolean isTaskFinished) {}

    /**
     * 统一的对话处理入口。
     */
    public DialogueResponse processMessage(String userMessage, String conversationId) {
        // 1. 检查是否存在正在进行的对话
        Optional<DialogueState> currentStateOpt = stateService.getState(conversationId);

        if (currentStateOpt.isPresent()) {
            // 2. 如果有，则继续处理这个进行中的任务
            return continueOngoingTask(userMessage, currentStateOpt.get());
        } else {
            // 3. 如果没有，则尝试开启一个新任务
            return startNewTask(userMessage, conversationId);
        }
    }

    private DialogueResponse startNewTask(String userMessage, String conversationId) {
        System.out.println("--- [对话流] 尝试开启新任务... ---");
        Set<String> allTools = metadataService.getToolSlots().keySet();
        var intentResult = extractIntentAndSlots(userMessage, allTools);

        if ("no_intent".equals(intentResult.intentName())) {
            return new DialogueResponse("抱歉，我不太理解您的意思，请说得更具体一些。", true);
        }

        // 创建一个新的对话状态
        DialogueState newState = new DialogueState(
            conversationId,
            intentResult.intentName(),
            metadataService.getRequiredSlots(intentResult.intentName()),
            new HashMap<>(intentResult.extractedSlots()),
            DialogueState.Status.GATHERING_INFO
        );

        stateService.saveState(conversationId, newState);
        return continueOngoingTask(userMessage, newState);
    }

    private DialogueResponse continueOngoingTask(String userMessage, DialogueState state) {
        // 如果是确认阶段，用户的回复有特殊含义
        if (state.status() == DialogueState.Status.CONFIRMATION_PENDING) {
            return handleConfirmation(userMessage, state);
        }

        // 否则，继续收集信息
        var intentResult = extractIntentAndSlots(userMessage, Set.of(state.intentName()));
        state.collectedSlots().putAll(intentResult.extractedSlots());

        // 检查是否所有槽位都已收集完毕
        Optional<String> nextSlot = findNextMissingSlot(state);
        if (nextSlot.isEmpty()) {
            // 所有槽位已满，进入确认阶段
            state = new DialogueState(state.conversationId(), state.intentName(), state.requiredSlots(), state.collectedSlots(), DialogueState.Status.CONFIRMATION_PENDING);
            stateService.saveState(state.conversationId(), state);
            return new DialogueResponse(buildConfirmationMessage(state), false);
        } else {
            // 提问下一个需要填充的槽位
            stateService.saveState(state.conversationId(), state);
            return new DialogueResponse(metadataService.getQuestionForSlot(nextSlot.get()), false);
        }
    }

    private DialogueResponse handleConfirmation(String userMessage, DialogueState state) {
        // 在确认阶段，启用特殊的modify_slot工具
        var intentResult = extractIntentAndSlots(userMessage, Set.of("modify_slot"));

        if ("modify_slot".equals(intentResult.intentName())) {
            // 用户想要修改信息
            Map<String, Object> args = intentResult.extractedSlots();
            state.collectedSlots().put((String)args.get("slot_name"), args.get("slot_value"));
            // 状态回退，重新确认
            DialogueState newState = new DialogueState(state.conversationId(), state.intentName(), state.requiredSlots(), state.collectedSlots(), DialogueState.Status.CONFIRMATION_PENDING);
            stateService.saveState(state.conversationId(), newState);
            return new DialogueResponse(buildConfirmationMessage(newState), false);
        }

        // 假设用户的其他肯定性回答意味着确认
        if (userMessage.contains("对") || userMessage.contains("是的") || userMessage.contains("没错")) {
            stateService.clearState(state.conversationId()); // 清理状态
            return new DialogueResponse("好的，正在为您办理...", true); // 这里应该触实际的工作流调用
        } else {
            // 用户否定但未提供修改信息，引导用户
            return new DialogueResponse("好的，请问是哪一项信息有误呢？您可以直接告诉我，例如‘职业是产品经理’。", false);
        }
    }

    private Optional<String> findNextMissingSlot(DialogueState state) {
        return state.requiredSlots().stream()
            .filter(slot -> !state.collectedSlots().containsKey(slot))
            .findFirst();
    }

    private String buildConfirmationMessage(DialogueState state) {
        StringBuilder sb = new StringBuilder("好的，请您确认信息：\n");
        state.collectedSlots().forEach((key, value) -> 
            sb.append(String.format("- %s: %s\n", key, value))
        );
        sb.append("信息正确吗？");
        return sb.toString();
    }

    private record IntentExtractionResult(String intentName, Map<String, Object> extractedSlots) {}

    private IntentExtractionResult extractIntentAndSlots(String userMessage, Set<String> tools) {
        var options = new ChatOptions(tools);
        var prompt = new Prompt(List.of(Map.of("role", "user", "content", userMessage)), options);
        var assistantMessage = chatClient.call(prompt).result();

        if (assistantMessage.toolCalls() != null && !assistantMessage.toolCalls().isEmpty()) {
            var toolCall = assistantMessage.toolCalls().get(0);
            try {
                Map<String, Object> slots = mapper.readValue(toolCall.function().arguments(), new TypeReference<>() {});
                return new IntentExtractionResult(toolCall.function().name(), slots);
            } catch (Exception e) {
                return new IntentExtractionResult("no_intent", Collections.emptyMap());
            }
        }
        return new IntentExtractionResult("no_intent", Collections.emptyMap());
    }
}
