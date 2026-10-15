package com.sentryagent.llm;

/**
 * 诊断能力的网关接口（可测性设计的核心一层）。
 *
 * <p>为什么要有这一层：图的节点逻辑（计数、路由、护栏）是确定性代码，
 * 它依赖的"模型输出"却是不确定的外部世界。把 LLM 出口收口到这个接口后：</p>
 * <ul>
 *   <li>生产实现 {@link ProdDiagnoserGateway}：委托 LangChain4j AiServices
 *       生成的 {@link Diagnoser} 代理（真实模型 + 工具循环 + 会话记忆）；</li>
 *   <li>测试/演示实现 {@link MockDiagnoserGateway}：按"队列堆积"剧本返回固定
 *       证据/假设/判定/计划文本——无 API key 也能把全图跑通、单测全绿。</li>
 * </ul>
 *
 * <p>切换开关：配置项 sentry.llm.enabled（默认 false）。这就是计划书
 * 风险条款"模型不稳定输出"的对策落地：LLM 只是网关背后的一种实现，
 * 图的拓扑、护栏、审批都不感知它是否存在。</p>
 */
public interface DiagnoserGateway {

    /**
     * 取证：返回一段证据清单文本。
     * 注意 verify 节点的"二次定向取证"同样走这个入口（把验证方法当 alert 传入），
     * 因此实现方要按传入语义分流，而不是无脑返回同一份内容。
     */
    String gatherEvidence(String service, String alert, Long incidentId, String sessionId);

    /** 假设生成：每行一条，格式 "假设描述 | 验证方法" */
    String hypothesize(String evidence, String sessionId);

    /** 假设验证：格式 "序号 | 成立/不成立/存疑 | 依据" */
    String verifyHypotheses(String hypotheses, String moreEvidence, String sessionId);

    /** 处置计划：每行一个动作，格式 "动作 | 目标 | 风险(LOW|HIGH) | 理由"；无可靠动作输出 NONE */
    String makePlan(String rootCause, String sessionId);

    /** 复盘报告 */
    String retrospect(Long incidentId, String transcript, String sessionId);
}
