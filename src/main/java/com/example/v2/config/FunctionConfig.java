package com.example.v2.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * 函数配置类
 * 这个类的唯一目的，是为大语言模型（LLM）提供一个所有可用工具函数的描述列表。
 * Spring AI会扫描所有@Bean，并使用@Description注解的内容来告知LLM每个函数的功能。
 * 真正的函数执行逻辑在各自的Service Bean中。
 */
@Configuration
public class FunctionConfig {

    @Bean
    @Description("为用户开立一个新的账户。需要提供学历、职业和住址信息。")
    public Function<String, com.example.v2.tool.OpenAccountService.Response> open_account() {
        // 这里返回null是故意的。因为这个Bean仅用于提供给LLM的元数据（特别是@Description）。
        // 实际的Bean实例由@Service("open_account")注解在OpenAccountService类上定义。
        return null;
    }

    @Bean
    @Description("为用户购买指定数量的股票。需要提供股票代码和购买数量。")
    public Function<String, com.example.v2.tool.StockPurchaseService.Response> stock_purchase() {
        // 同上，这个Bean仅用于元数据描述。
        return null;
    }
}
