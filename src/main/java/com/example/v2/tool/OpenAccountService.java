package com.example.v2.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.function.Function;

/**
 * 开户工具服务。
 * @Service("open_account") 定义了这个Bean的名称，必须与FunctionConfig中以及LLM期望的函数名一致。
 * 实现了Function<String, Response>接口，使其可以被工作流调度器统一调用。
 */
@Service("open_account")
public class OpenAccountService implements Function<String, OpenAccountService.Response> {

    private final ObjectMapper mapper = new ObjectMapper();

    // 定义工具的输入参数结构
    public record Request(
        @JsonProperty(required = true) String education,
        @JsonProperty(required = true) String occupation,
        @JsonProperty(required = true) String address
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
            System.out.println("正在执行开户操作，参数为: " + request);
            System.out.println("----------------------");

            // 3. 返回结构化的执行结果
            return new Response("success", "为职业为" + request.occupation() + "的用户开户成功。");
        } catch (JsonProcessingException e) {
            // 在真实的业务中，这里应该有更完善的异常处理
            throw new RuntimeException("解析开户参数时出错", e);
        }
    }
}
