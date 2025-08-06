package com.example.v2.controller;

import com.example.v2.service.DialogueFlowService;
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

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        try {
            // 调用统一的对话处理入口，传入消息和会话ID
            DialogueFlowService.DialogueResponse response = dialogueFlowService.processMessage(request.message(), request.conversationId());
            return ResponseEntity.ok(Map.of("reply", response.reply()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
