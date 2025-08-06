package com.example.v2.service;

import org.springframework.stereotype.Service;

/**
 * 系统级提示词模板服务。
 * 负责生成指导LLM行为的核心指令。
 */
@Service
public class PromptTemplateService {

    /**
     * 获取用于意图识别和槽位提取的系统级提示词。
     * @return 一个包含明确指令的字符串。
     */
    public String getSlotExtractionPrompt() {
        // 这个提示词明确地告诉LLM它的任务和行为准则
        return """
            你是一个专业的、任务驱动的AI业务办理助手。
            你的核心任务是分析用户的输入，并判断它是否匹配任何一个你所知道的可用工具（函数）。
            - 如果用户的意图匹配某个工具，请调用该工具，并尽你所能从用户的当前输入和对话历史中提取所有需要的参数。
            - 如果用户只表达了意图但没有提供任何参数，请调用工具，并将参数留空。
            - 如果用户的输入与任何可用工具都不匹配，请不要调用任何工具。
            """;
    }

    /**
     * 获取用于总结工作流结果的系统级提示词。
     * @param userMessage 用户的原始请求，用于提供上下文。
     * @param resultJson 工作流执行后的JSON结果。
     * @return 一个包含总结指令的字符串。
     */
    public String getSummarizationPrompt(String userMessage, String resultJson) {
        return String.format("你是一个专业的助手。用户的原始请求是: '%s'。后端工作流的执行结果是: %s。请用自然、友好的中文总结这个结果，不要输出JSON。", userMessage, resultJson);
    }

    /**
     * 获取用于处理纯闲聊的系统级提示词。
     * @return 一个包含闲聊指令的字符串。
     */
    public String getSimpleChatPrompt() {
        return "你是一个专业的金融助手，但现在用户只是在闲聊，请用友好、简洁的方式回复。";
    }
}
