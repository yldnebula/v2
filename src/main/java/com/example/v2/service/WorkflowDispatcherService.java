package com.example.v2.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Function;

/**
 * 工作流调度服务 (规则引擎)。
 * 这个服务完全不与LLM交互，它是一个可靠的、基于规则的路由器。
 */
@Service
public class WorkflowDispatcherService {

    @Autowired
    private ApplicationContext context; // 注入Spring的应用上下文，用于动态查找Bean

    /**
     * 根据意图名称，从Spring容器中查找对应的工具Bean并执行它。
     * @param intentName 意图名称，必须与工具Bean的名称完全匹配。
     * @param argumentsJson LLM提取的、用于调用工具的JSON字符串参数。
     * @return 一个包含执行结果的Map。
     */
    public Map<String, Object> dispatch(String intentName, String argumentsJson) {
        System.out.println("--- [工作流] 正在调度意图: " + intentName + " ---");
        try {
            // 根据意图名称，动态地从Spring容器中获取对应的Bean
            // 我们约定，所有工具Bean的名称都和它们的意图名称相同
            Function<String, ?> toolFunction = (Function<String, ?>) context.getBean(intentName);

            // 执行工具函数
            Object result = toolFunction.apply(argumentsJson);

            System.out.println("--- [工作流] 执行成功。 ---");
            return Map.of("status", "success", "data", result);

        } catch (Exception e) {
            System.err.println("--- [工作流] 调度或执行意图时出错: " + e.getMessage());
            e.printStackTrace();
            return Map.of("status", "error", "message", e.getMessage());
        }
    }
}
