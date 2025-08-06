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

@Service
public class DialogueFlowService {

    @Autowired private ChatClient chatClient;
    @Autowired private ApplicationContext context;
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
            return handleDigression(userMessage, null);
        }

        // 检查提取出的意图是否需要多轮填充
        Set<String> requiredSlots = metadataService.getRequiredSlots(intentResult.intentName());
        if (requiredSlots.isEmpty()) {
            // 如果不需要填充（一步到位的任务），直接执行工作流
            Map<String, Object> workflowResult = workflowDispatcher.dispatch(intentResult.intentName(), intentResult.extractedSlots());
            return handleWorkflowResult(workflowResult, intentResult.intentName(), intentResult.extractedSlots(), conversationId, null);
        } else {
            // 如果需要填充，则创建新的对话状态
            DialogueState newState = new DialogueState(conversationId, intentResult.intentName(), requiredSlots, new HashMap<>(intentResult.extractedSlots()), DialogueState.Status.GATHERING_INFO);
            stateService.saveState(conversationId, newState);
            return proceedState(newState);
        }
    }

    private DialogueResponse continueOngoingTask(String userMessage, DialogueState state) {
        System.out.println("--- [对话流] 继续进行中任务: " + state.intentName() + " ---");
        Set<String> digressionTools = Set.of("check_weather");
        var digressionIntent = extractIntentAndSlots(userMessage, digressionTools, state.conversationId());
        if (!"no_intent".equals(digressionIntent.intentName())) {
            return handleDigression(userMessage, state);
        }

        if (state.status() == DialogueState.Status.CONFIRMATION_PENDING) {
            return handleConfirmation(userMessage, state);
        }

        var mainIntentResult = extractIntentAndSlots(userMessage, Set.of(state.intentName()), state.conversationId());
        state.collectedSlots().putAll(mainIntentResult.extractedSlots());
        return proceedState(state);
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

            // 创建一个新的子任务状态，并记录下原始意图
            DialogueState.OriginatingIntent originatingIntent = new DialogueState.OriginatingIntent(originalIntent, originalArgs);
            DialogueState subTaskState = new DialogueState(conversationId, missingDependency, metadataService.getRequiredSlots(missingDependency), new HashMap<>(), DialogueState.Status.GATHERING_INFO, originatingIntent);
            stateService.saveState(conversationId, subTaskState);
            return new DialogueResponse(String.format("好的，收到您的%s请求。但在操作前，需要先为您办理%s。我们开始吧？%s", originalIntent, missingDependency, metadataService.getQuestionForSlot(findNextMissingSlot(subTaskState).get())), false);
        }

        // 检查是否存在父任务，如果存在，则回归主线
        if (parentIntent != null) {
            System.out.println("--- [对话流] 子任务完成，回归主线任务: " + parentIntent.intentName() + " ---");
            stateService.clearState(conversationId); // 清除子任务状态
            // 自动重新触发父任务
            return startNewTask(parentIntent.intentName() + " with args " + parentIntent.arguments(), conversationId);
        } else {
            stateService.clearState(conversationId);
            String summary = summarizeResult(originalIntent, result);
            return new DialogueResponse(summary, true);
        }
    }

    private DialogueResponse handleDigression(String userMessage, DialogueState currentState) {
        System.out.println("--- [对话流] 检测到偏离任务... ---");
        Set<String> digressionTools = Set.of("check_weather");
        var intentResult = extractIntentAndSlots(userMessage, digressionTools, currentState != null ? currentState.conversationId() : "temp_id");

        Map<String, Object> workflowResult = workflowDispatcher.dispatch(intentResult.intentName(), intentResult.extractedSlots());
        String digressionReply = summarizeResult(userMessage, workflowResult);

        if (currentState != null) {
            String mainTaskQuestion = findNextMissingSlot(currentState).map(metadataService::getQuestionForSlot).orElse(buildConfirmationMessage(currentState));
            return new DialogueResponse(digressionReply + "\n\n那么，回到我们正在办理的业务，" + mainTaskQuestion, false);
        }
        return new DialogueResponse(digressionReply, true);
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
                slots.put("userId", userId); // 自动注入userId
                return new IntentExtractionResult(toolCall.function().name(), slots);
            } catch (Exception e) { return new IntentExtractionResult("no_intent", Collections.emptyMap()); }
        }
        return new IntentExtractionResult("no_intent", Collections.emptyMap());
    }
}