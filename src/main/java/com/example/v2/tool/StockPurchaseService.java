package com.example.v2.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.function.Function;

/**
 * 股票购买工具服务。
 */
@Service("stock_purchase")
public class StockPurchaseService implements Function<String, StockPurchaseService.Response> {

    private final ObjectMapper mapper = new ObjectMapper();

    // 定义工具的输入参数结构
    public record Request(
        String ticker,
        int quantity,
        String userId // userId由DialogueFlowService自动注入
    ) {}

    // 定义工具的输出结果结构
    public record Response(String status, String message, String missingDependency) {
        // 为成功和失败场景提供便捷的构造器
        public static Response success(String message) {
            return new Response("SUCCESS", message, null);
        }
        public static Response preconditionFailed(String message, String missingDependency) {
            return new Response("PRECONDITION_FAILED", message, missingDependency);
        }
    }

    @Override
    public Response apply(String argumentsJson) {
        try {
            Request request = mapper.readValue(argumentsJson, Request.class);

            // --- 步骤 1: 前置条件检查 ---
            System.out.println("--- [工具执行] 正在检查用户 " + request.userId() + " 的前置条件... ---");
            if (!hasShareholderAccount(request.userId())) {
                System.out.println("--- [工具执行] 前置条件检查失败：缺少股东账户。 ---");
                return Response.preconditionFailed("用户缺少股东账户", "open_account");
            }
            System.out.println("--- [工具执行] 前置条件检查通过。 ---");

            // --- 步骤 2: 执行核心业务逻辑 ---
            System.out.println("--- [工具执行] 正在执行股票购买操作，参数为: " + request + " ---");
            return Response.success("成功购买 " + request.quantity() + " 股 " + request.ticker());

        } catch (JsonProcessingException e) {
            throw new RuntimeException("解析股票购买参数时出错", e);
        }
    }

    /**
     * 模拟一个检查用户是否拥有股东账户的服务。
     * 为了演示，我们假设除了“user_with_account”之外的所有用户都没有账户。
     */
    private boolean hasShareholderAccount(String userId) {
        return "user_with_account".equals(userId);
    }
}