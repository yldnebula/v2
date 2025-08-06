package com.example.v2.chat;

import com.example.v2.metadata.ToolMetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CustomOpenAiChatClient implements ChatClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final HttpHeaderProvider headerProvider;
    private final ToolMetadataService metadataService;
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    public CustomOpenAiChatClient(String apiKey, HttpHeaderProvider headerProvider, ToolMetadataService metadataService) {
        this.apiKey = apiKey;
        this.headerProvider = headerProvider;
        this.metadataService = metadataService;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<Map<String, Object>> messages = new ArrayList<>(prompt.messages());
        List<Map<String, Object>> tools = buildToolsJson(prompt.options().functions());
        Map<String, Object> responseBody = callApi(messages, tools);
        AssistantMessage assistantMessage = parseAssistantMessage(responseBody);

        if (assistantMessage.toolCalls() != null && !assistantMessage.toolCalls().isEmpty()) {
            messages.add(mapper.convertValue(assistantMessage, Map.class));
            for (AssistantMessage.ToolCall toolCall : assistantMessage.toolCalls()) {
                // Note: The actual tool execution is now handled by the WorkflowDispatcherService.
                // This client's responsibility ends at providing the tool call information.
            }
        }
        return new ChatResponse(assistantMessage);
    }

    private Map<String, Object> callApi(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        Map<String, String> dynamicHeaders = headerProvider.getHeaders();
        if (!CollectionUtils.isEmpty(dynamicHeaders)) {
            dynamicHeaders.forEach(headers::add);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-3.5-turbo");
        body.put("messages", messages);
        if (!tools.isEmpty()) {
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
        return metadataService.getAllTools().stream()
            .filter(tool -> toolNames.contains(tool.toolName()))
            .map(this::convertMetadataToToolJson)
            .collect(Collectors.toList());
    }

    private Map<String, Object> convertMetadataToToolJson(ToolMetadataService.ToolMetadata metadata) {
        Map<String, Object> properties = new HashMap<>();
        if (metadata.requestClass() != Map.class) {
            for (Field field : metadata.requestClass().getDeclaredFields()) {
                properties.put(field.getName(), Map.of("type", "string", "description", "")); // Simplified
            }
        }

        Map<String, Object> functionParams = Map.of("type", "object", "properties", properties);
        Map<String, Object> function = Map.of("name", metadata.toolName(), "description", metadata.description(), "parameters", functionParams);
        return Map.of("type", "function", "function", function);
    }
}
