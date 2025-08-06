package com.example.v2.service;

import com.example.v2.chat.AssistantMessage;
import com.example.v2.chat.ChatClient;
import com.example.v2.chat.ChatOptions;
import com.example.v2.chat.Prompt;
import com.example.v2.chat.SystemMessage;
import com.example.v2.metadata.ToolMetadataService;
import com.example.v2.state.ChatMessage;
import com.example.v2.state.DialogueState;
import com.example.v2.state.DialogueStateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DialogueFlowService {

    @Autowired private ChatClient chatClient;
    @Autowired private DialogueStateService stateService;
    @Autowired private ToolMetadataService metadataService;
    @Autowired private WorkflowDispatcherService workflowDispatcher;
    @Autowired private PromptTemplateService promptTemplateService; // 注入提示词服务
    private final ObjectMapper mapper = new ObjectMapper();

    public record DialogueResponse(String reply, boolean isTaskFinished) {}

    public DialogueResponse processMessage(String userMessage, String conversationId) {
        DialogueState state = stateService.getState(conversationId)
            .orElseGet(() -> new DialogueState(conversationId, null, null, new HashMap<>(), null, new ArrayList<>()));

        state.chatHistory().add(new ChatMessage("user", userMessage));

        DialogueResponse response = (state.intentName() != null)
            ? continueOngoingTask(state)
            : startNewTask(state);
        
        state.chatHistory().add(new ChatMessage("assistant", response.reply()));
        if (response.isTaskFinished()) {
            stateService.clearState(conversationId);
        } else {
            stateService.saveState(conversationId, state);
        }

        return response;
    }

    private DialogueResponse startNewTask(DialogueState state) {
        System.out.println("--- [对话流] 尝试开启新任务... ---");
        Set<String> allBusinessTools = metadataService.getBusinessToolNames();
        var intentResult = extractIntentAndSlots(state.chatHistory(), allBusinessTools, state.conversationId());

        if ("no_intent".equals(intentResult.intentName())) {
            return handleDigressionOrSimpleChat(state);
        }

        Set<String> requiredSlots = metadataService.getRequiredSlots(intentResult.intentName());
        DialogueState newState = new DialogueState(state.conversationId(), intentResult.intentName(), requiredSlots, new HashMap<>(intentResult.extractedSlots()), DialogueState.Status.GATHERING_INFO, state.chatHistory());
        
        return proceedState(newState);
    }

    private DialogueResponse continueOngoingTask(DialogueState state) {
        System.out.println("--- [对话流] 继续进行中任务: " + state.intentName() + " ---");
        // **修正点**: 将原有的闲聊/偏离逻辑统一到一个方法中处理
        return handlePossibleDigression(state);
    }

    private DialogueResponse handlePossibleDigression(DialogueState state) {
        Set<String> digressionTools = Set.of("check_weather");
        var intentResult = extractIntentAndSlots(state.chatHistory(), digressionTools, state.conversationId());

        if (!"no_intent".equals(intentResult.intentName())) {
            return executeDigression(intentResult, state);
        }

        // 如果不是偏离，则继续主线任务
        if (state.status() == DialogueState.Status.CONFIRMATION_PENDING) {
            return handleConfirmation(state);
        }

        var mainIntentResult = extractIntentAndSlots(state.chatHistory(), Set.of(state.intentName()), state.conversationId());
        state.collectedSlots().putAll(mainIntentResult.extractedSlots());
        return proceedState(state);
    }

    private DialogueResponse handleDigressionOrSimpleChat(DialogueState state) {
        Set<String> digressionTools = Set.of("check_weather");
        var intentResult = extractIntentAndSlots(state.chatHistory(), digressionTools, state.conversationId());

        if (!"no_intent".equals(intentResult.intentName())) {
            return executeDigression(intentResult, state);
        }
        return new DialogueResponse(handleSimpleChat(state.chatHistory()), true);
    }

    private DialogueResponse executeDigression(IntentExtractionResult intentResult, DialogueState currentState) {
        System.out.println("--- [对话流] 检测到偏离任务... ---");
        Map<String, Object> workflowResult = workflowDispatcher.dispatch(intentResult.intentName(), intentResult.extractedSlots());
        String digressionReply = summarizeResult(currentState.chatHistory(), workflowResult);

        if (currentState != null && currentState.intentName() != null) {
            String mainTaskQuestion = findNextMissingSlot(currentState).map(metadataService::getQuestionForSlot).orElse(buildConfirmationMessage(currentState));
            return new DialogueResponse(digressionReply + "\n\n那么，回到我们正在办理的业务，" + mainTaskQuestion, false);
        }
        return new DialogueResponse(digressionReply, true);
    }

    private String handleSimpleChat(List<ChatMessage> history) {
        System.out.println("--- [对话流] 处理纯闲聊... ---");
        String systemPrompt = promptTemplateService.getSimpleChatPrompt();
        var options = new ChatOptions(null);
        List<Object> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(history);
        var prompt = new Prompt(messages, options);
        return chatClient.call(prompt).result().content();
    }

    private DialogueResponse proceedState(DialogueState state) {
        Optional<String> nextSlot = findNextMissingSlot(state);
        if (nextSlot.isEmpty()) {
            DialogueState newState = new DialogueState(state.conversationId(), state.intentName(), state.requiredSlots(), state.collectedSlots(), DialogueState.Status.CONFIRMATION_PENDING, state.originatingIntent(), state.chatHistory());
            stateService.saveState(state.conversationId(), newState);
            return new DialogueResponse(buildConfirmationMessage(newState), false);
        } else {
            stateService.saveState(state.conversationId(), state);
            return new DialogueResponse(metadataService.getQuestionForSlot(nextSlot.get()), false);
        }
    }

    private DialogueResponse handleConfirmation(DialogueState state) {
        var intentResult = extractIntentAndSlots(state.chatHistory(), Set.of("modify_slot"), state.conversationId());

        if ("modify_slot".equals(intentResult.intentName())) {
            Map<String, Object> args = intentResult.extractedSlots();
            state.collectedSlots().put((String)args.get("slot_name"), args.get("slot_value"));
            DialogueState newState = new DialogueState(state.conversationId(), state.intentName(), state.requiredSlots(), state.collectedSlots(), DialogueState.Status.CONFIRMATION_PENDING, state.originatingIntent(), state.chatHistory());
            return new DialogueResponse(buildConfirmationMessage(newState), false);
        }

        String lastUserMessage = state.chatHistory().get(state.chatHistory().size() - 1).content();
        if (lastUserMessage.contains("对") || lastUserMessage.contains("是的") || lastUserMessage.contains("没错")) {
            Map<String, Object> workflowResult = workflowDispatcher.dispatch(state.intentName(), state.collectedSlots());
            return handleWorkflowResult(workflowResult, state.intentName(), state.collectedSlots(), state, state.originatingIntent());
        } else {
            return new DialogueResponse("好的，请问是哪一项信息有误呢？", false);
        }
    }

    private DialogueResponse handleWorkflowResult(Map<String, Object> result, String originalIntent, Map<String, Object> originalArgs, DialogueState state, DialogueState.OriginatingIntent parentIntent) {
        String status = (String) result.get("status");
        if ("PRECONDITION_FAILED".equals(status)) {
            Map<String, String> data = (Map<String, String>) result.get("data");
            String missingDependency = data.get("missingDependency");
            System.out.println("--- [对话流] 检测到前置条件失败，需要引导用户解决: " + missingDependency + " ---");

            DialogueState.OriginatingIntent originatingIntent = new DialogueState.OriginatingIntent(originalIntent, originalArgs);
            DialogueState subTaskState = new DialogueState(state.conversationId(), missingDependency, metadataService.getRequiredSlots(missingDependency), new HashMap<>(), DialogueState.Status.GATHERING_INFO, originatingIntent, state.chatHistory());
            stateService.saveState(state.conversationId(), subTaskState);
            return new DialogueResponse(String.format("好的，收到您的%s请求。但在操作前，需要先为您办理%s。我们开始吧？%s", originalIntent, missingDependency, metadataService.getQuestionForSlot(findNextMissingSlot(subTaskState).get())), false);
        }

        if (parentIntent != null) {
            System.out.println("--- [对话流] 子任务完成，回归主线任务: " + parentIntent.intentName() + " ---");
            stateService.clearState(state.conversationId());
            return startNewTask(new DialogueState(state.conversationId(), null, null, null, null, state.chatHistory()));
        } else {
            String summary = summarizeResult(state.chatHistory(), result);
            return new DialogueResponse(summary, true);
        }
    }

    private String summarizeResult(List<ChatMessage> history, Map<String, Object> workflowResult) {
        try {
            String resultJson = mapper.writeValueAsString(workflowResult);
            String systemPrompt = promptTemplateService.getSummarizationPrompt(history.get(history.size()-1).content(), resultJson);
            List<Object> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            var prompt = new Prompt(messages, new ChatOptions(null));
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

    private IntentExtractionResult extractIntentAndSlots(List<ChatMessage> history, Set<String> tools, String userId) {
        // **修正点**: 将系统提示词作为第一条消息发送给LLM
        String systemPrompt = promptTemplateService.getSlotExtractionPrompt();
        List<Object> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(history);

        var options = new ChatOptions(tools);
        var prompt = new Prompt(messages, options);
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
