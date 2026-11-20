package com.sentryagent.llm;

import com.sentryagent.graph.Hypothesis;

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
 *
 * <p>结构化契约（不足清单第 4 项）：hypothesize/verifyHypotheses 的输入输出
 * 均为"每行一个 JSON 对象"的文本（假设 {"cause","confidence","verifyAction"}、
 * 判定 {"index","conclusion","reason"}），由 graph 包的 HypothesisParser 与
 * 前端 parsers.js 按"JSON 优先、旧文本兜底"的同一解析次序消费——
 * mock 与 Prod 的 prompt 契约、解析器、前端三方改格式必须同步。</p>
 */
public interface DiagnoserGateway {

    /**
     * 取证：返回一段证据清单文本（首轮 = 告警直接驱动的取证）。
     * 历史备注：verify 节点的二次取证曾走这个入口（把验证方法当 alert 传入，
     * 请求带 "补充取证:" 前缀）——不足清单第 4 项起改走
     * {@link #gatherVerifyEvidence(Hypothesis, String)} 的定向入口；
     * 本方法的补充轮分流语义保留为兼容路径（旧剧本/手工调用不再被误分流）。
     */
    String gatherEvidence(String service, String alert, Long incidentId, String sessionId);

    /**
     * 定向补充取证（不足清单第 4 项"对症下药"的取证面）：按一条结构化假设的
     * verifyAction 精准取证，而不是把整段证据重读或拼进 alert 泛化请求。
     * mock 实现按假设回包带 [补充证据-定向] 前缀的针对性文本（且逐假设差异化，
     * 防 appender 通道去重塌缩）；生产实现委托 Diagnoser 的定向取证 prompt。
     */
    String gatherVerifyEvidence(Hypothesis hypothesis, String sessionId);

    /**
     * 假设生成：每行一条 JSON
     * {@code {"cause":...,"confidence":0-100,"verifyAction":"..."}}
     * （生产侧 Diagnoser.hypothesize 的 prompt 同步要求产 JSON；
     * 旧文本 "假设描述 | 验证方法" 由解析器兜底兼容，渐进迁移不破坏旧剧本）。
     */
    String hypothesize(String evidence, String sessionId);

    /**
     * 假设验证：接收 JSON 假设列表文本 + 定向补充证据，逐行返回 JSON 判定
     * {@code {"index":N,"conclusion":"成立|不成立|存疑","reason":"..."}}
     * （判定序号即假设行序，前端按序挂卡；旧文本格式兜底兼容）。
     */
    String verifyHypotheses(String hypotheses, String moreEvidence, String sessionId);

    /**
     * 处置计划：每行一个动作，格式 "动作 | 目标 | 风险(LOW|HIGH) | 理由"；
     * 无可靠动作输出 NONE。入参 rootCause = 图侧 plan 节点结构化提取的
     * "判定成立"根因（confirmedCauses），空串即无成立根因。
     */
    String makePlan(String rootCause, String sessionId);

    /** 复盘报告 */
    String retrospect(Long incidentId, String transcript, String sessionId);

    /**
     * 该会话（sessionId = "incident-{id}"）累计消耗的 token 数（input+output 合计口径）。
     * W3 起图侧把它累加进 IncidentState.TOTAL_TOKENS 通道（事件维度总账），
     * 并作为 sentry.token-budget-per-incident 预算护栏的读数——确定性兜底
     * 与步数护栏同款写法：超预算无条件 escalate，排最前保证优先级。
     */
    long tokensUsed(String sessionId);
}
