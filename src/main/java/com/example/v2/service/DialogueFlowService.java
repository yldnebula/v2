package com.example.v2.service;

import com.example.v2.chat.ChatClient;
import com.example.v2.chat.ChatOptions;
import com.example.v2.chat.Prompt;
import com.example.v2.metadata.ToolMetadataService;
import com.example.v2.state.DialogueState;
import com.example.v2.state.DialogueStateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 对话流服务 (LLM专家) - 具备多轮槽位填充及偏离处理能力
 */
@Service
public class DialogueFlowService {

    @Autowired private ChatClient chatClient;
    @Autowired private ApplicationContext context;
    @Autowired private DialogueStateService stateService;
    @Autowired private ToolMetadataService metadataService;
    @Autowired private WorkflowDispatcherService workflowDispatcher; // 注入工作流调度器
    private final ObjectMapper mapper = new ObjectMapper();

    // 对话返回结果，包含回复和任务是否结束的标志
    public record DialogueResponse(String reply, boolean isTaskFinished) {}

    /**
     * 统一的对话处理入口。
     */
    public DialogueResponse processMessage(String userMessage, String conversationId) {
        Optional<DialogueState> currentStateOpt = stateService.getState(conversationId);
        return currentStateOpt.map(state -> continueOngoingTask(userMessage, state))
                              .orElseGet(() -> startNewTask(userMessage, conversationId));
    }

    private DialogueResponse startNewTask(String userMessage, String conversationId) {
        System.out.println("--- [对话流] 尝试开启新任务... ---");
        Set<String> allBusinessTools = metadataService.getToolSlots().keySet();
        var intentResult = extractIntentAndSlots(userMessage, allBusinessTools);

        if ("no_intent".equals(intentResult.intentName())) {
            return handleDigression(userMessage, null); // 作为闲聊处理
        }

        DialogueState newState = new DialogueState(conversationId, intentResult.intentName(), metadataService.getRequiredSlots(intentResult.intentName()), new HashMap<>(intentResult.extractedSlots()), DialogueState.Status.GATHERING_INFO);
        stateService.saveState(conversationId, newState);
        return proceedState(userMessage, newState);
    }

    private DialogueResponse continueOngoingTask(String userMessage, DialogueState state) {
        System.out.println("--- [对话流] 继续进行中任务: " + state.intentName() + " ---");
        // 1. 优先检查是否为偏离意图 (例如查天气)
        Set<String> digressionTools = Set.of("check_weather"); // 可配置的偏离工具集
        var digressionIntent = extractIntentAndSlots(userMessage, digressionTools);
        if (!"no_intent".equals(digressionIntent.intentName())) {
            return handleDigression(userMessage, state);
        }

        // 2. 如果不是偏离，则处理主线任务
        if (state.status() == DialogueState.Status.CONFIRMATION_PENDING) {
            return handleConfirmation(userMessage, state);
        }

        // 3. 否则，继续收集槽位信息
        var mainIntentResult = extractIntentAndSlots(userMessage, Set.of(state.intentName()));
        state.collectedSlots().putAll(mainIntentResult.extractedSlots());
        return proceedState(userMessage, state);
    }

    private DialogueResponse proceedState(String userMessage, DialogueState state) {
        Optional<String> nextSlot = findNextMissingSlot(state);
        if (nextSlot.isEmpty()) {
            // 所有槽位已满，进入确认阶段
            DialogueState newState = new DialogueState(state.conversationId(), state.intentName(), state.requiredSlots(), state.collectedSlots(), DialogueState.Status.CONFIRMATION_PENDING);
            stateService.saveState(state.conversationId(), newState);
            return new DialogueResponse(buildConfirmationMessage(newState), false);
        } else {
            // 提问下一个需要填充的槽位
            stateService.saveState(state.conversationId(), state);
            return new DialogueResponse(metadataService.getQuestionForSlot(nextSlot.get()), false);
        }
    }

    private DialogueResponse handleConfirmation(String userMessage, DialogueState state) {
        var intentResult = extractIntentAndSlots(userMessage, Set.of("modify_slot"));

        if ("modify_slot".equals(intentResult.intentName())) {
            Map<String, Object> args = intentResult.extractedSlots();
            state.collectedSlots().put((String)args.get("slot_name"), args.get("slot_value"));
            DialogueState newState = new DialogueState(state.conversationId(), state.intentName(), state.requiredSlots(), state.collectedSlots(), DialogueState.Status.CONFIRMATION_PENDING);
            stateService.saveState(state.conversationId(), newState);
            return new DialogueResponse(buildConfirmationMessage(newState), false);
        }

        if (userMessage.contains("对") || userMessage.contains("是的") || userMessage.contains("没错")) {
            // 确认成功，执行工作流
            Map<String, Object> workflowResult = workflowDispatcher.dispatch(state.intentName(), state.collectedSlots());
            stateService.clearState(state.conversationId());
            String summary = summarizeResult(userMessage, workflowResult);
            return new DialogueResponse(summary, true);
        } else {
            return new DialogueResponse("好的，请问是哪一项信息有误呢？您可以直接告诉我，例如‘职业是产品经理’。", false);
        }
    }

    private DialogueResponse handleDigression(String userMessage, DialogueState currentState) {
        System.out.println("--- [对话流] 检测到偏离任务... ---");
        Set<String> digressionTools = Set.of("check_weather");
        var intentResult = extractIntentAndSlots(userMessage, digressionTools);

        Map<String, Object> workflowResult = workflowDispatcher.dispatch(intentResult.intentName(), intentResult.extractedSlots());
        String digressionReply = summarizeResult(userMessage, workflowResult);

        // 如果当前有主线任务，则在回复偏离任务后，追加主线任务的提问
        if (currentState != null) {
            Optional<String> nextSlot = findNextMissingSlot(currentState);
            String mainTaskQuestion = nextSlot.map(metadataService::getQuestionForSlot)
                                              .orElse(buildConfirmationMessage(currentState)); // 如果槽位已满，则重新确认
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
        return state.requiredSlots().stream().filter(slot -> !state.collectedSlots().containsKey(slot)).findFirst();
    }

    private String buildConfirmationMessage(DialogueState state) {
        String collectedInfo = state.collectedSlots().entrySet().stream().map(e -> String.format("- %s: %s", e.getKey(), e.getValue())).collect(Collectors.joining("\n"));
        return String.format("好的，请您确认信息：\n%s\n信息正确吗？", collectedInfo);
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
            } catch (Exception e) { return new IntentExtractionResult("no_intent", Collections.emptyMap()); }
        }
        return new IntentExtractionResult("no_intent", Collections.emptyMap());
    }
}