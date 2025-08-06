package com.example.v2.service;

import com.example.v2.chat.AssistantMessage;
import com.example.v2.chat.ChatClient;
import com.example.v2.chat.ChatOptions;
import com.example.v2.chat.Prompt;
import com.example.v2.metadata.ToolMetadataService;
import com.example.v2.state.DialogueState;
import com.example.v2.state.DialogueStateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 对话流服务 (LLM专家) - 最终版
 * 具备多轮槽位填充、对话偏离处理、前置条件检查、回归主线以及纯闲聊处理能力。
 */
@Service
public class DialogueFlowService {

    @Autowired private ChatClient chatClient;
    @Autowired private DialogueStateService stateService;
    @Autowired private ToolMetadataService metadataService;
    @Autowired private WorkflowDispatcherService workflowDispatcher;
    private final ObjectMapper mapper = new ObjectMapper();

    public record DialogueResponse(String reply, boolean isTaskFinished) {}

    public DialogueResponse processMessage(String userMessage, String conversationId) {
        Optional<DialogueState> currentStateOpt = stateService.getState(conversationId);
        return currentStateOpt.map(state -> continueOngoingTask(userMessage, state))
                              .orElseGet(() -> startNewTask(userMessage, conversationId));
    }

    private DialogueResponse startNewTask(String userMessage, String conversationId) {
        System.out.println("--- [对话流] 尝试开启新任务... ---");
        Set<String> allBusinessTools = metadataService.getBusinessToolNames();
        var intentResult = extractIntentAndSlots(userMessage, allBusinessTools, conversationId);

        if ("no_intent".equals(intentResult.intentName())) {
            // **修正点**: 如果不是业务意图，则尝试作为偏离/闲聊处理
            return handleDigressionOrSimpleChat(userMessage, null);
        }

        Set<String> requiredSlots = metadataService.getRequiredSlots(intentResult.intentName());
        if (requiredSlots.isEmpty()) {
            Map<String, Object> workflowResult = workflowDispatcher.dispatch(intentResult.intentName(), intentResult.extractedSlots());
            return handleWorkflowResult(workflowResult, intentResult.intentName(), intentResult.extractedSlots(), conversationId, null);
        } else {
            DialogueState newState = new DialogueState(conversationId, intentResult.intentName(), requiredSlots, new HashMap<>(intentResult.extractedSlots()), DialogueState.Status.GATHERING_INFO, null);
            stateService.saveState(conversationId, newState);
            return proceedState(newState);
        }
    }

    private DialogueResponse continueOngoingTask(String userMessage, DialogueState state) {
        System.out.println("--- [对话流] 继续进行中任务: " + state.intentName() + " ---");
        return handleDigressionOrSimpleChat(userMessage, state);
    }

    /**
     * 统一处理偏离或闲聊。这是对话处理的核心分发器。
     */
    private DialogueResponse handleDigressionOrSimpleChat(String userMessage, DialogueState currentState) {
        // 1. 优先检查是否为已知的“偏离”工具（如查天气）
        Set<String> digressionTools = Set.of("check_weather");
        var intentResult = extractIntentAndSlots(userMessage, digressionTools, currentState != null ? currentState.conversationId() : "temp_id");

        if (!"no_intent".equals(intentResult.intentName())) {
            // 2. 如果是偏离工具，则执行并回归主线
            System.out.println("--- [对话流] 检测到偏离任务... ---");
            Map<String, Object> workflowResult = workflowDispatcher.dispatch(intentResult.intentName(), intentResult.extractedSlots());
            String digressionReply = summarizeResult(userMessage, workflowResult);

            if (currentState != null) {
                String mainTaskQuestion = findNextMissingSlot(currentState).map(metadataService::getQuestionForSlot).orElse(buildConfirmationMessage(currentState));
                return new DialogueResponse(digressionReply + "\n\n那么，回到我们正在办理的业务，" + mainTaskQuestion, false);
            }
            return new DialogueResponse(digressionReply, true);
        }

        // 3. 如果不是任何已知工具，则作为“纯闲聊”处理
        if (currentState != null) {
            // 如果当前有主线任务，闲聊后需要回归主线
            String chatReply = handleSimpleChat(userMessage);
            String mainTaskQuestion = findNextMissingSlot(currentState).map(metadataService::getQuestionForSlot).orElse(buildConfirmationMessage(currentState));
            return new DialogueResponse(chatReply + "\n\n回到正题，" + mainTaskQuestion, false);
        } else {
            // 如果当前没有主线任务，就是纯粹的闲聊
            return new DialogueResponse(handleSimpleChat(userMessage), true);
        }
    }

    /**
     * 处理不带任何工具的、开放式的纯闲聊。
     */
    private String handleSimpleChat(String userMessage) {
        System.out.println("--- [对话流] 处理纯闲聊... ---");
        String systemPrompt = "你是一个专业的金融助手，但现在用户只是在闲聊，请用友好、简洁的方式回复。";
        var options = new ChatOptions(null); // 不启用任何工具
        var prompt = new Prompt(List.of(new com.example.v2.chat.SystemMessage(systemPrompt), new com.example.v2.chat.UserMessage(userMessage)), options);
        return chatClient.call(prompt).result().content();
    }

    private DialogueResponse proceedState(DialogueState state) {
        Optional<String> nextSlot = findNextMissingSlot(state);
        if (nextSlot.isEmpty()) {
            DialogueState newState = new DialogueState(state.conversationId(), state.intentName(), state.requiredSlots(), state.collectedSlots(), DialogueState.Status.CONFIRMATION_PENDING, state.originatingIntent());
            stateService.saveState(state.conversationId(), newState);
            return new DialogueResponse(buildConfirmationMessage(newState), false);
        } else {
            stateService.saveState(state.conversationId(), state);
            return new DialogueResponse(metadataService.getQuestionForSlot(nextSlot.get()), false);
        }
    }

    private DialogueResponse handleConfirmation(String userMessage, DialogueState state) {
        var intentResult = extractIntentAndSlots(userMessage, Set.of("modify_slot"), state.conversationId());

        if ("modify_slot".equals(intentResult.intentName())) {
            Map<String, Object> args = intentResult.extractedSlots();
            state.collectedSlots().put((String)args.get("slot_name"), args.get("slot_value"));
            DialogueState newState = new DialogueState(state.conversationId(), state.intentName(), state.requiredSlots(), state.collectedSlots(), DialogueState.Status.CONFIRMATION_PENDING, state.originatingIntent());
            stateService.saveState(state.conversationId(), newState);
            return new DialogueResponse(buildConfirmationMessage(newState), false);
        }

        if (userMessage.contains("对") || userMessage.contains("是的") || userMessage.contains("没错")) {
            Map<String, Object> workflowResult = workflowDispatcher.dispatch(state.intentName(), state.collectedSlots());
            return handleWorkflowResult(workflowResult, state.intentName(), state.collectedSlots(), state.conversationId(), state.originatingIntent());
        } else {
            return new DialogueResponse("好的，请问是哪一项信息有误呢？", false);
        }
    }

    private DialogueResponse handleWorkflowResult(Map<String, Object> result, String originalIntent, Map<String, Object> originalArgs, String conversationId, DialogueState.OriginatingIntent parentIntent) {
        String status = (String) result.get("status");
        if ("PRECONDITION_FAILED".equals(status)) {
            Map<String, String> data = (Map<String, String>) result.get("data");
            String missingDependency = data.get("missingDependency");
            System.out.println("--- [对话流] 检测到前置条件失败，需要引导用户解决: " + missingDependency + " ---");

            DialogueState.OriginatingIntent originatingIntent = new DialogueState.OriginatingIntent(originalIntent, originalArgs);
            DialogueState subTaskState = new DialogueState(conversationId, missingDependency, metadataService.getRequiredSlots(missingDependency), new HashMap<>(), DialogueState.Status.GATHERING_INFO, originatingIntent);
            stateService.saveState(conversationId, subTaskState);
            return new DialogueResponse(String.format("好的，收到您的%s请求。但在操作前，需要先为您办理%s。我们开始吧？%s", originalIntent, missingDependency, metadataService.getQuestionForSlot(findNextMissingSlot(subTaskState).get())), false);
        }

        if (parentIntent != null) {
            System.out.println("--- [对话流] 子任务完成，回归主线任务: " + parentIntent.intentName() + " ---");
            stateService.clearState(conversationId);
            return startNewTask(parentIntent.intentName() + " with args " + parentIntent.arguments(), conversationId);
        } else {
            stateService.clearState(conversationId);
            String summary = summarizeResult(originalIntent, result);
            return new DialogueResponse(summary, true);
        }
    }

    private String summarizeResult(String userMessage, Map<String, Object> workflowResult) {
        try {
            String resultJson = mapper.writeValueAsString(workflowResult);
            String systemPrompt = String.format("你是一个专业的助手。用户的请求是: '%s'。后端执行结果是: %s。请用自然、友好的中文总结这个结果。", userMessage, resultJson);
            var prompt = new Prompt(List.of(Map.of("role", "system", "content", systemPrompt)), new ChatOptions(null));
            return chatClient.call(prompt).result().content();
        } catch (JsonProcessingException e) { return "处理结果时出现错误。"; }
    }

    private Optional<String> findNextMissingSlot(DialogueState state) {
        return state.requiredSlots().stream().filter(slot -> !state.collectedSlots().containsKey(slot) || state.collectedSlots().get(slot) == null).findFirst();
    }

    private String buildConfirmationMessage(DialogueState state) {
        String collectedInfo = state.collectedSlots().entrySet().stream().map(e -> String.format("- %s: %s", e.getKey(), e.getValue())).collect(Collectors.joining("\n"));
        return String.format("好的，请您确认信息：\n%s\n信息正确吗？", collectedInfo);
    }

    private record IntentExtractionResult(String intentName, Map<String, Object> extractedSlots) {}

    private IntentExtractionResult extractIntentAndSlots(String userMessage, Set<String> tools, String userId) {
        var options = new ChatOptions(tools);
        var prompt = new Prompt(List.of(Map.of("role", "user", "content", userMessage)), options);
        var assistantMessage = chatClient.call(prompt).result();

        if (assistantMessage.toolCalls() != null && !assistantMessage.toolCalls().isEmpty()) {
            var toolCall = assistantMessage.toolCalls().get(0);
            try {
                Map<String, Object> slots = mapper.readValue(toolCall.function().arguments(), new TypeReference<>() {});
                slots.put("userId", userId);
                return new IntentExtractionResult(toolCall.function().name(), slots);
            } catch (Exception e) { return new IntentExtractionResult("no_intent", Collections.emptyMap()); }
        }
        return new IntentExtractionResult("no_intent", Collections.emptyMap());
    }
}
