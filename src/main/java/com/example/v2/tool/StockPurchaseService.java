package com.example.v2.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.function.Function;

/**
 * 股票购买工具服务。
 * @Service("stock_purchase") 定义了这个Bean的名称，必须与FunctionConfig中以及LLM期望的函数名一致。
 */
@Service("stock_purchase")
public class StockPurchaseService implements Function<String, StockPurchaseService.Response> {

    private final ObjectMapper mapper = new ObjectMapper();

    // 定义工具的输入参数结构
    public record Request(
        @JsonProperty(required = true) String ticker, // 股票代码
        @JsonProperty(required = true) int quantity  // 购买数量
    ) {}

    // 定义工具的输出结果结构
    public record Response(String status, String message) {}

    /**
     * 工具的执行入口。
     * @param argumentsJson LLM传来的、包含所有参数的JSON字符串。
     * @return 执行结果。
     */
    @Override
    public Response apply(String argumentsJson) {
        try {
            // 1. 将JSON字符串参数反序列化为Java对象
            Request request = mapper.readValue(argumentsJson, Request.class);

            // 2. 执行具体的业务逻辑（此处为模拟）
            System.out.println("--- [工具执行] ---");
            System.out.println("正在执行股票购买操作，参数为: " + request);
            System.out.println("----------------------");

            // 3. 返回结构化的执行结果
            return new Response("success", "成功购买 " + request.quantity() + " 股 " + request.ticker());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("解析股票购买参数时出错", e);
        }
    }
}
