package com.sentryagent.llm;

import com.sentryagent.tool.EvidenceTools;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型与诊断网关的装配（LangChain4j 侧全部入口都在这一个类，方便换 provider）。
 *
 * <p>配置开关 sentry.llm.enabled 决定上下文里装哪套 {@link DiagnoserGateway}：</p>
 * <ul>
 *   <li>true：ChatModel + AiServices 代理 Diagnoser + Prod 网关（真实模型链路，
 *       需要 OPENAI_API_KEY）；</li>
 *   <li>缺省/false：只装 Mock 网关（按剧本回包）——无 key 环境、全部单测、
 *       README 演示链路都走这里，OpenAI 客户端根本不会被创建。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class LlmConfig {

    /**
     * OpenAI 兼容 ChatModel。
     * timeout/maxRetries 显式设置（默认值会随版本变，显式=可预期，同项目二 Feign 的立场）。
     * 仅在 llm.enabled=true 时存在，避免无 key 环境拉起真实模型栈。
     */
    @Bean
    @ConditionalOnProperty(name = "sentry.llm.enabled", havingValue = "true")
    public ChatModel chatModel(@Value("${sentry.llm.api-key}") String apiKey,
                               @Value("${sentry.llm.base-url}") String baseUrl,
                               @Value("${sentry.llm.model}") String model) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(0.2)
                .build();
    }

    /**
     * Diagnoser 代理 = 提示词接口 + 模型 + 6 个工具 + 会话记忆。
     *
     * <p>@MemoryId（sessionId=incidentId 字符串）：每个事件一条独立记忆线，
     * 图里不同节点共享同一记忆——"假设"能看到"取证"的完整上下文，
     * 而 State 只传结构化摘要，二者分工见计划书 4.2。</p>
     */
    @Bean
    @ConditionalOnProperty(name = "sentry.llm.enabled", havingValue = "true")
    public Diagnoser diagnoser(ChatModel chatModel, EvidenceTools tools) {
        return AiServices.builder(Diagnoser.class)
                .chatModel(chatModel)
                .tools(tools)
                .chatMemoryProvider(memoryId ->
                        MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    /** 生产网关：委托上面的 Diagnoser 代理（仅 llm.enabled=true） */
    @Bean
    @ConditionalOnProperty(name = "sentry.llm.enabled", havingValue = "true")
    public DiagnoserGateway prodDiagnoserGateway(Diagnoser diagnoser) {
        log.info("DiagnoserGateway = Prod（真实 LLM 链路）");
        return new ProdDiagnoserGateway(diagnoser);
    }

    /** Mock 网关：缺省实现。havingValue=false + matchIfMissing=true => 配置缺失也生效 */
    @Bean
    @ConditionalOnProperty(name = "sentry.llm.enabled", havingValue = "false", matchIfMissing = true)
    public DiagnoserGateway mockDiagnoserGateway() {
        log.info("DiagnoserGateway = Mock（sentry.llm.enabled=false，剧本化输出，不发起任何网络调用）");
        return new MockDiagnoserGateway();
    }

    /**
     * 审批令牌注册表：common 层是零 Spring 的纯 POJO（分层纪律），
     * 所以由这里显式 @Bean 装配——"领域对象不依赖框架，框架来装配它"。
     */
    @Bean
    public com.sentryagent.tool.ApprovalRegistry approvalRegistry() {
        return new com.sentryagent.tool.ApprovalRegistry();
    }
}
