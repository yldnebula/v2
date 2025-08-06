package com.example.v2.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * 函数配置类
 * 这个类的唯一目的，是为大语言模型（LLM）提供一个所有可用工具函数的描述列表。
 */
@Configuration
public class FunctionConfig {

    // DTO for the special modify_slot function
    public record ModifySlotRequest(@JsonProperty(required = true) String slot_name, @JsonProperty(required = true) String slot_value){}

    @Bean
    @Description("为用户开立一个新的账户。需要提供学历、职业和住址信息。")
    public Function<String, com.example.v2.tool.OpenAccountService.Response> open_account() {
        return null; // Placeholder for metadata
    }

    @Bean
    @Description("为用户购买指定数量的股票。需要提供股票代码和购买数量。")
    public Function<String, com.example.v2.tool.StockPurchaseService.Response> stock_purchase() {
        return null; // Placeholder for metadata
    }

    @Bean
    @Description("查询指定城市的天气情况。如果用户没有指定城市，可以默认为杭州。")
    public Function<String, com.example.v2.tool.WeatherToolService.Response> check_weather() {
        return null; // Placeholder for metadata
    }

    @Bean
    @Description("当用户在最后确认信息阶段，明确指出某一项信息错误并提供新值时使用此功能。")
    public Function<ModifySlotRequest, String> modify_slot() {
        // This is a special function that doesn't have a real tool implementation.
        // It's used by the DialogueFlowService to handle state changes.
        return null;
    }
}
