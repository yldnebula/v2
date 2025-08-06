package com.example.v2.metadata;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 工具元数据服务 (工具的说明书)。
 * 集中管理所有工具的元信息，例如需要哪些参数（槽位）以及如何提问。
 * 在生产环境中，这些信息可以从配置文件、数据库或服务发现中心加载。
 */
@Service
public class ToolMetadataService {

    // 定义每个工具所需的槽位
    private static final Map<String, Set<String>> TOOL_SLOTS = Map.of(
        "open_account", Set.of("education", "occupation", "address"),
        "stock_purchase", Set.of("ticker", "quantity")
    );

    // 定义每个槽位的提问话术
    private static final Map<String, String> SLOT_QUESTIONS = Map.of(
        "education", "请问您的学历是？",
        "occupation", "您的职业是什么呢？",
        "address", "您的常住地址是哪里？",
        "ticker", "好的，请问您想购买哪只股票的代码？",
        "quantity", "您计划购买多少股？"
    );

    public Set<String> getRequiredSlots(String intentName) {
        return TOOL_SLOTS.getOrDefault(intentName, Set.of());
    }

    public String getQuestionForSlot(String slotName) {
        return SLOT_QUESTIONS.getOrDefault(slotName, "请提供 " + slotName + " 的信息。");
    }
}
