package com.example.v2.metadata;

import com.example.v2.tool.OpenAccountService;
import com.example.v2.tool.StockPurchaseService;
import com.example.v2.tool.WeatherToolService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具元数据服务 (工具的“唯一真实来源”)。
 * 集中管理所有工具的元信息，包括其描述、所需槽位和提问话术。
 */
@Service
public class ToolMetadataService {

    /**
     * 内部数据结构，用于定义一个完整的工具元数据。
     */
    private record ToolMetadata(
        String toolName,
        String description,
        Set<String> requiredSlots,
        Class<?> requestClass // 用于后续通过反射构建参数
    ) {}

    private static final List<ToolMetadata> TOOL_METADATA_LIST = List.of(
        new ToolMetadata(
            "open_account",
            "为用户开立一个新的账户。需要提供学历、职业和住址信息。",
            Set.of("education", "occupation", "address"),
            OpenAccountService.Request.class
        ),
        new ToolMetadata(
            "stock_purchase",
            "为用户购买指定数量的股票。需要提供股票代码和购买数量。",
            Set.of("ticker", "quantity"),
            StockPurchaseService.Request.class
        ),
        new ToolMetadata(
            "check_weather",
            "查询指定城市的天气情况。如果用户没有指定城市，可以默认为杭州。",
            Set.of(), // 天气查询没有必需的槽位
            WeatherToolService.Request.class
        ),
        new ToolMetadata(
            "modify_slot",
            "当用户在最后确认信息阶段，明确指出某一项信息错误并提供新值时使用此功能。",
            Set.of("slot_name", "slot_value"),
            Map.class // 使用一个通用的Map
        )
    );

    private static final Map<String, String> SLOT_QUESTIONS = Map.of(
        "education", "请问您的学历是？",
        "occupation", "您的职业是什么呢？",
        "address", "您的常住地址是哪里？",
        "ticker", "好的，请问您想购买哪只股票的代码？",
        "quantity", "您计划购买多少股？"
    );

    public Set<String> getRequiredSlots(String intentName) {
        return TOOL_METADATA_LIST.stream()
            .filter(t -> t.toolName.equals(intentName))
            .findFirst()
            .map(ToolMetadata::requiredSlots)
            .orElse(Set.of());
    }

    public String getQuestionForSlot(String slotName) {
        return SLOT_QUESTIONS.getOrDefault(slotName, "请提供 " + slotName + " 的信息。");
    }

    public List<ToolMetadata> getAllTools() {
        return TOOL_METADATA_LIST;
    }

    public Set<String> getBusinessToolNames() {
        return TOOL_METADATA_LIST.stream()
            .filter(t -> !t.toolName.equals("check_weather") && !t.toolName.equals("modify_slot"))
            .map(ToolMetadata::toolName)
            .collect(Collectors.toSet());
    }
}