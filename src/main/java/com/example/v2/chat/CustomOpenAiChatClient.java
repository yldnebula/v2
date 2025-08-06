package com.example.v2.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Description;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 我们手写的OpenAI ChatClient核心实现。
 * 这个类封装了所有与OpenAI API交互的底层逻辑，包括函数调用的完整流程。
 */
public class CustomOpenAiChatClient implements ChatClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationContext context;
    private final String apiKey;
    private final HttpHeaderProvider headerProvider; // 请求头提供者
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    public CustomOpenAiChatClient(String apiKey, ApplicationContext context, HttpHeaderProvider headerProvider) {
        this.apiKey = apiKey;
        this.context = context;
        this.headerProvider = headerProvider;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<Map<String, Object>> messages = new ArrayList<>(prompt.messages());
        List<Map<String, Object>> tools = buildToolsJson(prompt.options().functions());

        // 第一次调用API
        Map<String, Object> responseBody = callApi(messages, tools);
        AssistantMessage assistantMessage = parseAssistantMessage(responseBody);

        // 检查是否需要调用工具
        if (assistantMessage.toolCalls() != null && !assistantMessage.toolCalls().isEmpty()) {
            messages.add(mapper.convertValue(assistantMessage, Map.class));

            // 执行所有工具调用
            for (AssistantMessage.ToolCall toolCall : assistantMessage.toolCalls()) {
                Object toolResult = executeTool(toolCall.function().name(), toolCall.function().arguments());
                try {
                    messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", toolCall.id(),
                        "name", toolCall.function().name(),
                        "content", mapper.writeValueAsString(toolResult)
                    ));
                } catch (Exception e) { throw new RuntimeException(e); }
            }

            // 第二次调用API，并附上工具执行结果
            responseBody = callApi(messages, tools);
            assistantMessage = parseAssistantMessage(responseBody);
        }

        return new ChatResponse(assistantMessage);
    }

    private Object executeTool(String toolName, String toolArgs) {
        Function<String, ?> toolFunction = (Function<String, ?>) context.getBean(toolName);
        return toolFunction.apply(toolArgs);
    }

    private Map<String, Object> callApi(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        
        // 每次调用API前，都从Provider获取动态请求头
        Map<String, String> dynamicHeaders = headerProvider.getHeaders();
        if (!CollectionUtils.isEmpty(dynamicHeaders)) {
            dynamicHeaders.forEach(headers::add);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-3.5-turbo");
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        System.out.println("--- [自定义客户端] 正在调用OpenAI，请求头: " + headers.toSingleValueMap());
        return restTemplate.postForObject(OPENAI_API_URL, requestEntity, Map.class);
    }

    private AssistantMessage parseAssistantMessage(Map<String, Object> responseBody) {
        Map<String, Object> choice = ((List<Map<String, Object>>) responseBody.get("choices")).get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        return mapper.convertValue(message, AssistantMessage.class);
    }

    private List<Map<String, Object>> buildToolsJson(Set<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (String beanName : toolNames) {
            Object toolBean = context.getBean(beanName);
            Method beanMethod = Arrays.stream(toolBean.getClass().getMethods()).filter(m -> m.getName().equals("apply")).findFirst().orElse(null);
            if (beanMethod == null) continue;

            Description description = context.findAnnotationOnBean(beanName, Description.class);
            Parameter parameter = beanMethod.getParameters()[0];
            Class<?> paramType = parameter.getType();

            Map<String, Object> properties = Arrays.stream(paramType.getDeclaredFields())
                .collect(Collectors.toMap(f -> f.getName(), f -> Map.of("type", "string")));

            Map<String, Object> functionParams = Map.of("type", "object", "properties", properties);
            Map<String, Object> function = Map.of("name", beanName, "description", description.value(), "parameters", functionParams);
            toolList.add(Map.of("type", "function", "function", function));
        }
        return toolList;
    }
}