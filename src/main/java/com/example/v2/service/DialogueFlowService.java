package com.example.v2.service;

import com.example.v2.chat.AssistantMessage;
import com.example.v2.chat.ChatClient;
import com.example.v2.chat.ChatOptions;
import com.example.v2.chat.Prompt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话流服务 (LLM专家)
 * 这个服务专门负责与大语言模型（LLM）进行所有交互。
 */
@Service
public class DialogueFlowService {

    private final ChatClient chatClient; // 注入我们自定义的ChatClient接口
    private final ApplicationContext context;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 用于封装意图识别结果的DTO。
     */
    public record IntentExtractionResult(String intentName, String argumentsJson) {}

    @Autowired
    public DialogueFlowService(ChatClient chatClient, ApplicationContext context) {
        this.chatClient = chatClient;
        this.context = context;
    }

    /**
     * 使用LLM来识别用户的意图并提取参数。
     */
    public IntentExtractionResult determineIntent(String userMessage) {
        // 动态发现所有可用的工具
        Set<String> availableTools = context.getBeansWithAnnotation(Description.class).keySet();
        System.out.println("--- [对话流] 正在识别意图。可用工具: " + availableTools + " ---");

        // 准备Prompt，并启用所有发现的工具
        var options = new ChatOptions(availableTools);
        var prompt = new Prompt(List.of(Map.of("role", "user", "content", userMessage)), options);

        // 调用我们自建的ChatClient
        AssistantMessage assistantMessage = chatClient.call(prompt).result();

        // 解析结果
        if (assistantMessage.toolCalls() != null && !assistantMessage.toolCalls().isEmpty()) {
            var toolCall = assistantMessage.toolCalls().get(0);
            System.out.println("--- [对话流] 意图已识别: " + toolCall.function().name() + " ---");
            return new IntentExtractionResult(toolCall.function().name(), toolCall.function().arguments());
        }

        return new IntentExtractionResult("no_intent", null);
    }

    /**
     * 使用LLM将工作流的执行结果总结成一句自然的、人类可读的回复。
     */
    public String summarizeResult(String userMessage, Map<String, Object> workflowResult) throws JsonProcessingException {
        System.out.println("--- [对话流] 正在总结工作流结果... ---");
        String resultJson = mapper.writeValueAsString(workflowResult);
        String systemPrompt = """
            你是一个专业的金融助手。用户的原始请求是: '{userMessage}'.
            后端的工作流已经执行完毕，其返回的JSON结果如下: {resultJson}.
            你的任务是将这个JSON结果用自然、友好的中文总结出来。
            不要输出JSON本身，只需要提供一句流畅的、对话式的回复。
            """
            .replace("{userMessage}", userMessage)
            .replace("{resultJson}", resultJson);

        // 总结时不需要任何工具
        var options = new ChatOptions(null);
        var prompt = new Prompt(List.of(Map.of("role", "system", "content", systemPrompt)), options);
        return chatClient.call(prompt).result().content();
    }
}