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
 */
record ChatRequest(String message, String conversationId) {}

/**
 * 聊天接口的总控制器 (总指挥)。
 */
@RestController
public class ChatController {

    @Autowired
    private DialogueFlowService dialogueFlowService;

    @Autowired
    private WorkflowDispatcherService workflowDispatcherService; // 暂时保留，用于最终执行

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        try {
            // 调用统一的对话处理入口
            DialogueFlowService.DialogueResponse response = dialogueFlowService.processMessage(request.message(), request.conversationId());

            // 如果任务完成（例如，所有信息已收集并确认），则可以触发工作流
            if (response.isTaskFinished()) {
                // 在一个完整的应用中，这里会从DialogueState中获取最终的意图和参数
                // 然后调用 workflowDispatcherService.dispatch(...)
                System.out.println("--- [控制器] 任务已完成，准备执行工作流（当前为模拟）。 ---");
            }

            return ResponseEntity.ok(Map.of("reply", response.reply()));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
