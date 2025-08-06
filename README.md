# 智能业务办理助手 - 架构设计文档

本文档详细阐述了一个基于纯对话的智能业务办理助手的后端架构设计。该设计旨在将大语言模型（LLM）的自然语言理解能力与传统Java业务逻辑的可靠性、确定性相结合。

## 1. 核心设计思想：对话流与工作流分离

为了构建一个既智能又可靠的系统，我们将核心架构分为两个主要部分：

- **对话流 (Dialogue Flow)**: 由一个专门的`DialogueFlowService`负责。它充当系统的“AI大脑”，专门负责与大语言模型（LLM）进行交互。其职责是**理解**和**表达**。
    - **理解**：解析用户的自然语言，识别其背后的意图（Intent），并提取出执行该意图所需的参数（Entities）。
    - **表达**：将后端业务系统返回的、结构化的执行结果（如JSON或Java对象），转换成自然的、人类可读的语言，回复给用户。

- **工作流 (Workflow)**: 由`WorkflowDispatcherService`和各个工具服务（`tool`包）负责。它充当系统的“执行手臂”，完全不与LLM交互，只负责**可靠地、按规则执行**业务逻辑。
    - **调度**：根据对话流识别出的意图名称，精确地找到并调用对应的业务工具。
    - **执行**：运行具体的Java代码，操作数据库、调用第三方API等，完成实际的业务办理。

这种分离确保了AI的“创造性”和“不确定性”被严格限制在语言理解和生成环节，而核心业务的执行过程是100%可预测和可靠的。

## 2. 系统架构与组件职责

```
+----------------+      (1. 用户消息)      +-----------------------+      (2. 识别意图)      +-----------------------------+
|   前端助手     | ---------------------> |    ChatController     | ---------------------> |     DialogueFlowService     |
+----------------+      (总指挥)           +-----------------------+      (AI大脑)             +-----------------------------+
      ^                                             |                                         |
      |                                             | (3. 意图和参数)                         | (LLM API Call)
      | (6. 最终回复)                                 |                                         v
      |                                             v                                   +-----------------------------+
      |                                   +-----------------------------+      (4. 执行结果)      |      Workflow (工具)        |
      |                                   | WorkflowDispatcherService   | ---------------------> | - OpenAccountService        |
      |                                   | (规则引擎)                  |                       | - StockPurchaseService      |
      +-----------------------------------|-----------------------------+ <-------------------- | - ... (更多工具)          |
                                          ^      (5. 总结结果)      |                       +-----------------------------+
                                          |                         |
                                          +-------------------------+
```

### 组件详解

- **`ChatController`**: **总指挥**。
  - 作为HTTP请求的入口，接收前端的纯文本消息。
  - 负责编排`DialogueFlowService`和`WorkflowDispatcherService`之间的调用顺序，完成“识别 -> 执行 -> 总结”的完整流程。

- **`DialogueFlowService`**: **AI大脑**。
  - **`determineIntent(message)`**: 核心方法。它会自动从Spring容器中发现所有可用的工具函数（通过`FunctionConfig`），将它们的描述信息全部发送给LLM，让LLM从用户消息中判断应该调用哪个函数，并返回函数的名称和参数。
  - **`summarizeResult(message, result)`**: 将工作流返回的Java对象或Map，连同用户原始请求一起发送给LLM，让LLM生成一句通顺的总结性回复。

- **`WorkflowDispatcherService`**: **规则引擎**。
  - 接收`determineIntent`返回的函数名和参数。
  - 使用Spring的`ApplicationContext`，根据函数名（例如`"open_account"`）精确地查找到对应的Bean。
  - 调用该Bean的`apply`方法，执行业务逻辑，并返回一个结构化的结果。

- **`tool`包 (例如 `OpenAccountService.java`)**: **具体工具**。
  - 每个工具都是一个独立的Service，并使用`@Service("tool_name")`注解来定义其在Spring容器中的唯一名称，这个名称必须与`FunctionConfig`中定义的函数名一致。
  - 它们都实现`java.util.function.Function<String, Response>`接口，统一接收一个JSON字符串作为参数，并返回一个结构化的Response对象。

- **`config/FunctionConfig.java`**: **工具的“黄页”**。
  - 这个类的唯一作用是为LLM提供一个所有可用工具的“功能描述清单”。
  - Spring AI通过扫描此处的`@Bean`定义和`@Description`注解，来构建发送给LLM的`tools`参数。
  - Bean本身返回`null`，因为实际的执行逻辑由`tool`包中同名的`@Service` Bean提供。

## 3. 如何扩展新功能

得益于当前架构，增加一个全新的业务办理功能（例如“修改用户信息”）变得极其简单：

1.  **创建新工具**: 在`com.example.v2.tool`包下，创建一个新的服务类，例如`UpdateInfoService.java`。
    - 让它实现`Function<String, Response>`接口。
    - 在类上添加`@Service("update_info")`注解。
    - 在`apply`方法中实现具体的业务逻辑。

2.  **注册工具描述**: 在`FunctionConfig.java`中，添加一个新的`@Bean`方法来描述这个新工具。
    ```java
    @Bean
    @Description("修改用户的个人信息，例如地址或联系方式。")
    public Function<String, com.example.v2.tool.UpdateInfoService.Response> update_info() {
        return null;
    }
    ```

完成以上两步后，无需修改任何核心代码，新的“修改信息”功能便已自动集成到系统中。当用户说出类似“帮我把地址改一下”时，`DialogueFlowService`就能自动识别出`update_info`这个意图。

## 4. 技术栈与运行

- **Spring Boot**: `2.4.13`
- **Spring AI**: `0.8.1` (与Spring Boot 2.x兼容的稳定版本)
- **Java**: `17`

**运行前配置**:

- 修改 `src/main/resources/application.properties` 文件，填入您的OpenAI API密钥：
  ```properties
  spring.ai.openai.api-key=YOUR_OPENAI_API_KEY
  ```

**启动项目**:

在项目根目录 `/Users/a0/myGemini/v2/` 下执行Maven命令：
```bash
mvn spring-boot:run
```
