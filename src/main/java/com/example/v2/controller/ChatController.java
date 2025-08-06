package com.example.v2.controller;

import com.example.v2.service.DialogueFlowService;
import com.example.v2.service.WorkflowDispatcherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 前端请求的数据传输对象 (DTO)。
 * 在这个纯对话模式下，只需要用户消息。
 */
record ChatRequest(String message) {}

/**
 * 聊天接口的总控制器 (总指挥)。
 * 负责编排整个“对话流 -> 工作流 -> 对话流”的调用流程。
 */
@RestController
public class ChatController {

    @Autowired
    private DialogueFlowService dialogueFlowService; // 对话流服务，负责与LLM交互

    @Autowired
    private WorkflowDispatcherService workflowDispatcherService; // 工作流服务，负责执行具体业务

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        try {
            // 步骤 1: 使用对话流服务来识别用户的意图。
            // 在这个模式下，我们让对话流服务自己去发现所有可用的工具。
            DialogueFlowService.IntentExtractionResult intentResult = dialogueFlowService.determineIntent(request.message());

            String finalReply;
            if ("no_intent".equals(intentResult.intentName())) {
                // 如果没有识别出特定意图，返回一个通用回复。
                finalReply = "抱歉，我不太理解您的意思，请说得更具体一些。";
            } else {
                // 步骤 2: 使用工作流服务来执行该意图对应的业务逻辑。
                Map<String, Object> workflowResult = workflowDispatcherService.dispatch(
                    intentResult.intentName(),
                    intentResult.argumentsJson()
                );

                // 步骤 3: 将工作流的执行结果，再次交给对话流服务，让LLM生成一个自然的总结。
                finalReply = dialogueFlowService.summarizeResult(request.message(), workflowResult);
            }

            return ResponseEntity.ok(Map.of("reply", finalReply));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}