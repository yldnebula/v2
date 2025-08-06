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
 * 具备多轮槽位填充、对话偏离处理、前置条件检查和回归主线等所有高级功能。
 */
@Service
public class DialogueFlowService {

    @Autowired private ChatClient chatClient;
    @Autowired private DialogueStateService stateService;
    @Autowired private ToolMetadataService metadataService;
    @Autowired private WorkflowDispatcherService workflowDispatcher;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 对话返回结果的封装。
     * @param reply 要回复给用户的文本。
     * @param isTaskFinished 任务是否已彻底结束（用于告知上层是否需要清理状态）。
     */
    public record DialogueResponse(String reply, boolean isTaskFinished) {}

    /**
     * 统一的对话处理入口，所有外部调用都应通过此方法。
     * @param userMessage 用户的原始消息。
     * @param conversationId 唯一的会话ID。
     * @return 一个包含回复和任务状态的响应对象。
     */
    public DialogueResponse processMessage(String userMessage, String conversationId) {
        // 检查是否存在正在进行的对话状态
        Optional<DialogueState> currentStateOpt = stateService.getState(conversationId);
        // 如果存在，则继续任务；否则，开启新任务。
        return currentStateOpt.map(state -> continueOngoingTask(userMessage, state))
                              .orElseGet(() -> startNewTask(userMessage, conversationId));
    }

    /**
     * 当没有进行中任务时，尝试根据用户输入开启一个新任务。
     */
    private DialogueResponse startNewTask(String userMessage, String conversationId) {
        System.out.println("--- [对话流] 尝试开启新任务... ---");
        // 从元数据服务获取所有已定义的业务工具，用于意图识别
        Set<String> allBusinessTools = metadataService.getBusinessToolNames();
        var intentResult = extractIntentAndSlots(userMessage, allBusinessTools, conversationId);

        // 如果没有识别出任何业务意图，则作为“偏离”或“闲聊”处理
        if ("no_intent".equals(intentResult.intentName())) {
            return handleDigression(userMessage, null);
        }

        // 检查识别出的意图是否需要多轮对话来填充槽位
        Set<String> requiredSlots = metadataService.getRequiredSlots(intentResult.intentName());
        if (requiredSlots.isEmpty()) {
            // 如果不需要填充（例如查天气），则直接执行工作流
            Map<String, Object> workflowResult = workflowDispatcher.dispatch(intentResult.intentName(), intentResult.extractedSlots());
            return handleWorkflowResult(workflowResult, intentResult.intentName(), intentResult.extractedSlots(), conversationId, null);
        } else {
            // 如果需要填充，则创建新的对话状态，并开始信息收集
            // **修正点**: 使用完整的构造函数，明确将“父任务”设置为空(null)
            DialogueState newState = new DialogueState(conversationId, intentResult.intentName(), requiredSlots, new HashMap<>(intentResult.extractedSlots()), DialogueState.Status.GATHERING_INFO, null);
            stateService.saveState(conversationId, newState);
            return proceedState(newState);
        }
    }

    /**
     * 当存在进行中任务时，处理用户的后续输入。
     */
    private DialogueResponse continueOngoingTask(String userMessage, DialogueState state) {
        System.out.println("--- [对话流] 继续进行中任务: " + state.intentName() + " ---");
        // 1. 优先检查用户的输入是否是一个“偏离任务”（如查天气）
        Set<String> digressionTools = Set.of("check_weather");
        var digressionIntent = extractIntentAndSlots(userMessage, digressionTools, state.conversationId());
        if (!"no_intent".equals(digressionIntent.intentName())) {
            return handleDigression(userMessage, state);
        }

        // 2. 如果不是偏离，检查当前是否处于“等待确认”状态
        if (state.status() == DialogueState.Status.CONFIRMATION_PENDING) {
            return handleConfirmation(userMessage, state);
        }

        // 3. 如果既不是偏离也不是确认，那么用户的输入就是为了填充槽位
        var mainIntentResult = extractIntentAndSlots(userMessage, Set.of(state.intentName()), state.conversationId());
        state.collectedSlots().putAll(mainIntentResult.extractedSlots());
        return proceedState(state);
    }

    /**
     * 驱动状态机向前推进：检查是否需要提问下一个问题，或者进入确认阶段。
     */
    private DialogueResponse proceedState(DialogueState state) {
        Optional<String> nextSlot = findNextMissingSlot(state);
        if (nextSlot.isEmpty()) {
            // 所有槽位已满，将状态变更为“等待确认”，并构建确认信息
            DialogueState newState = new DialogueState(state.conversationId(), state.intentName(), state.requiredSlots(), state.collectedSlots(), DialogueState.Status.CONFIRMATION_PENDING, state.originatingIntent());
            stateService.saveState(state.conversationId(), newState);
            return new DialogueResponse(buildConfirmationMessage(newState), false);
        } else {
            // 提问下一个需要填充的槽位
            stateService.saveState(state.conversationId(), state);
            return new DialogueResponse(metadataService.getQuestionForSlot(nextSlot.get()), false);
        }
    }

    /**
     * 处理用户在“确认阶段”的回复。
     */
    private DialogueResponse handleConfirmation(String userMessage, DialogueState state) {
        // 在确认阶段，启用特殊的“修改槽位”工具
        var intentResult = extractIntentAndSlots(userMessage, Set.of("modify_slot"), state.conversationId());

        if ("modify_slot".equals(intentResult.intentName())) {
            // 如果用户想要修改信息，则更新状态并重新确认
            Map<String, Object> args = intentResult.extractedSlots();
            state.collectedSlots().put((String)args.get("slot_name"), args.get("slot_value"));
            DialogueState newState = new DialogueState(state.conversationId(), state.intentName(), state.requiredSlots(), state.collectedSlots(), DialogueState.Status.CONFIRMATION_PENDING, state.originatingIntent());
            stateService.saveState(state.conversationId(), newState);
            return new DialogueResponse(buildConfirmationMessage(newState), false);
        }

        // 如果用户表达了肯定的意思，则认为确认成功
        if (userMessage.contains("对") || userMessage.contains("是的") || userMessage.contains("没错")) {
            // 调用工作流执行最终业务
            Map<String, Object> workflowResult = workflowDispatcher.dispatch(state.intentName(), state.collectedSlots());
            // 处理工作流的返回结果（可能会回归主线）
            return handleWorkflowResult(workflowResult, state.intentName(), state.collectedSlots(), state.conversationId(), state.originatingIntent());
        } else {
            // 如果用户否定但未提供明确修改，则引导用户
            return new DialogueResponse("好的，请问是哪一项信息有误呢？您可以直接告诉我，例如‘职业是产品经理’。", false);
        }
    }

    /**
     * 处理工作流执行后的返回结果，这是实现“前置条件检查”和“回归主线”的关键。
     */
    private DialogueResponse handleWorkflowResult(Map<String, Object> result, String originalIntent, Map<String, Object> originalArgs, String conversationId, DialogueState.OriginatingIntent parentIntent) {
        String status = (String) result.get("status");
        if ("PRECONDITION_FAILED".equals(status)) {
            // 如果工作流因为前置条件不满足而失败
            Map<String, String> data = (Map<String, String>) result.get("data");
            String missingDependency = data.get("missingDependency");
            System.out.println("--- [对话流] 检测到前置条件失败，需要引导用户解决: " + missingDependency + " ---");

            // 创建一个新的子任务状态，并记录下原始意图作为“父任务”
            DialogueState.OriginatingIntent originatingIntent = new DialogueState.OriginatingIntent(originalIntent, originalArgs);
            DialogueState subTaskState = new DialogueState(conversationId, missingDependency, metadataService.getRequiredSlots(missingDependency), new HashMap<>(), DialogueState.Status.GATHERING_INFO, originatingIntent);
            stateService.saveState(conversationId, subTaskState);
            // 生成引导用户开始子任务的回复
            return new DialogueResponse(String.format("好的，收到您的%s请求。但在操作前，需要先为您办理%s。我们开始吧？%s", originalIntent, missingDependency, metadataService.getQuestionForSlot(findNextMissingSlot(subTaskState).get())), false);
        }

        // 如果当前任务是一个子任务，并且已成功完成
        if (parentIntent != null) {
            System.out.println("--- [对话流] 子任务完成，回归主线任务: " + parentIntent.intentName() + " ---");
            stateService.clearState(conversationId); // 清除子任务状态
            // 自动重新触发父任务
            return startNewTask(parentIntent.intentName() + " with args " + parentIntent.arguments(), conversationId);
        } else {
            // 如果是一个独立的任务成功完成
            stateService.clearState(conversationId);
            String summary = summarizeResult(originalIntent, result);
            return new DialogueResponse(summary, true);
        }
    }

    /**
     * 处理用户的“偏离”请求（例如在办理业务时查天气）。
     */
    private DialogueResponse handleDigression(String userMessage, DialogueState currentState) {
        System.out.println("--- [对话流] 检测到偏离任务... ---");
        var intentResult = extractIntentAndSlots(userMessage, Set.of("check_weather"), currentState != null ? currentState.conversationId() : "temp_id");

        // 执行偏离任务的工作流
        Map<String, Object> workflowResult = workflowDispatcher.dispatch(intentResult.intentName(), intentResult.extractedSlots());
        String digressionReply = summarizeResult(userMessage, workflowResult);

        // 如果当前有主线任务，则在回复偏离任务后，追加主线任务的提问，实现“回归主线”
        if (currentState != null) {
            String mainTaskQuestion = findNextMissingSlot(currentState).map(metadataService::getQuestionForSlot).orElse(buildConfirmationMessage(currentState));
            return new DialogueResponse(digressionReply + "\n\n那么，回到我们正在办理的业务，" + mainTaskQuestion, false);
        }
        return new DialogueResponse(digressionReply, true);
    }

    /**
     * 调用LLM，将结构化的执行结果总结成自然语言。
     */
    private String summarizeResult(String userMessage, Map<String, Object> workflowResult) {
        try {
            String resultJson = mapper.writeValueAsString(workflowResult);
            String systemPrompt = String.format("你是一个专业的助手。用户的请求是: '%s'。后端执行结果是: %s。请用自然、友好的中文总结这个结果。", userMessage, resultJson);
            var prompt = new Prompt(List.of(Map.of("role", "system", "content", systemPrompt)), new ChatOptions(null));
            return chatClient.call(prompt).result().content();
        } catch (JsonProcessingException e) { return "处理结果时出现错误。"; }
    }

    /**
     * 从当前状态中，查找下一个需要被填充的槽位。
     */
    private Optional<String> findNextMissingSlot(DialogueState state) {
        return state.requiredSlots().stream().filter(slot -> !state.collectedSlots().containsKey(slot) || state.collectedSlots().get(slot) == null).findFirst();
    }

    /**
     * 构建最终的确认信息字符串。
     */
    private String buildConfirmationMessage(DialogueState state) {
        String collectedInfo = state.collectedSlots().entrySet().stream().map(e -> String.format("- %s: %s", e.getKey(), e.getValue())).collect(Collectors.joining("\n"));
        return String.format("好的，请您确认信息：\n%s\n信息正确吗？", collectedInfo);
    }

    /**
     * 封装了调用LLM进行意图识别和槽位提取的底层逻辑。
     */
    private record IntentExtractionResult(String intentName, Map<String, Object> extractedSlots) {}

    private IntentExtractionResult extractIntentAndSlots(String userMessage, Set<String> tools, String userId) {
        var options = new ChatOptions(tools);
        var prompt = new Prompt(List.of(Map.of("role", "user", "content", userMessage)), options);
        var assistantMessage = chatClient.call(prompt).result();

        if (assistantMessage.toolCalls() != null && !assistantMessage.toolCalls().isEmpty()) {
            var toolCall = assistantMessage.toolCalls().get(0);
            try {
                Map<String, Object> slots = mapper.readValue(toolCall.function().arguments(), new TypeReference<>() {});
                slots.put("userId", userId); // 自动将会话ID作为userId注入参数
                return new IntentExtractionResult(toolCall.function().name(), slots);
            } catch (Exception e) { return new IntentExtractionResult("no_intent", Collections.emptyMap()); }
        }
        return new IntentExtractionResult("no_intent", Collections.emptyMap());
    }
}