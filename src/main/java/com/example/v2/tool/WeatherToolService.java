package com.example.v2.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.function.Function;

/**
 * 天气查询工具服务（用于演示“偏离处理”）。
 * @Service("check_weather") 定义了这个Bean的名称。
 */
@Service("check_weather")
public class WeatherToolService implements Function<String, WeatherToolService.Response> {

    private final ObjectMapper mapper = new ObjectMapper();

    // 天气工具的输入参数
    public record Request(@JsonProperty(required = true) String city) {}

    // 天气工具的输出结果
    public record Response(String city, String weather, String temperature) {}

    @Override
    public Response apply(String argumentsJson) {
        try {
            // 模拟提取参数，即使没有提供城市，也给一个默认值
            Request request = argumentsJson.contains("city") 
                ? mapper.readValue(argumentsJson, Request.class)
                : new Request("杭州");

            System.out.println("--- [工具执行] ---");
            System.out.println("正在执行天气查询操作，参数为: " + request);
            System.out.println("----------------------");

            // 模拟返回固定的天气数据
            return new Response(request.city(), "晴朗", "25°C");
        } catch (JsonProcessingException e) {
            throw new RuntimeException("解析天气查询参数时出错", e);
        }
    }
}
