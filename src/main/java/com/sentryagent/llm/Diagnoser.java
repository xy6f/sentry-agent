package com.sentryagent.llm;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 诊断助手接口（LangChain4j AiServices 声明式代理）：
 * 这里只写"提示词契约"，方法实现由框架生成——调用时自动完成
 * 模型请求、@Tool 函数调用循环、对话记忆管理。
 *
 * <p>设计说明（面试点）：为什么把"取证"做成带工具的自主循环，而"假设/验证/计划"
 * 是无工具的单次推理？——取证过程不确定（要看结果决定下一步查什么），交给
 * function calling；而推理产出需要被图上的确定性逻辑消费（计数、路由），
 * 必须是可控的单次结构化输出。"该智能的地方智能，该确定的地方确定"。</p>
 */
public interface Diagnoser {

    /** 自主取证：允许模型自行决定调用哪些读 Tool、调几次 */
    @SystemMessage("""
            你是 SRE 故障诊断工程师的取证模块。目标：收集与告警相关的客观证据。
            纪律：只调用查询类工具；工具返回失败时如实记录，不要编造数据。
            输出一段简明证据清单（每条带来源）。
            """)
    @UserMessage("""
            服务 {service} 告警：{alert}
            事件编号 {incidentId}。请取证。
            """)
    String gatherEvidence(@V("service") String service,
                          @V("alert") String alert,
                          @V("incidentId") Long incidentId,
                          @MemoryId String sessionId);

    /**
     * 定向补充取证（不足清单第 4 项）：verify 节点对每条结构化假设按其
     * verifyAction 单独发起的"对症下药"取证。与 gatherEvidence 的区别：
     * 这里有明确的验证对象与验证方法，prompt 约束"只收集与该验证方法
     * 直接相关的证据"，不允许漫无目的地重读整段证据。
     */
    @SystemMessage("""
            你是 SRE 取证模块的定向验证子模块。针对给定的根因假设与验证方法
            做"对症下药"的定向取证：只收集与该验证方法直接相关的证据，
            不做无关查询、不整段重读已有证据。
            纪律：只调用查询类工具；工具返回失败时如实记录，不要编造数据。
            输出一段简明证据清单（每条带来源）。
            """)
    @UserMessage("""
            根因假设：{cause}
            验证方法：{verifyAction}
            请按验证方法定向取证。
            """)
    String gatherVerifyEvidence(@V("cause") String cause,
                                @V("verifyAction") String verifyAction,
                                @MemoryId String sessionId);

    /**
     * 假设生成：结构化输出（不足清单第 4 项，W2 曾是自由文本"假设描述 | 验证方法"）。
     * 注意这里的 JSON 行格式是三方共享契约：mock 网关 MockDiagnoserGateway.hypothesize
     * 的剧本输出、后端 HypothesisParser.parse（JSON 优先、旧文本兜底）与前端
     * parsers.js.parseHypotheses 按同一格式消费——改格式必须三处同步。
     */
    @SystemMessage("""
            你是根因分析模块。基于证据最多列 3 个可验证的根因假设。
            每个假设必须给出"如何验证"（具体查什么数据、预期看到什么）。
            格式（严格遵守）每行一条 JSON，除 JSON 行外不要输出任何其他文字：
            {"cause":"假设描述","confidence":0到100的整数,"verifyAction":"具体验证动作"}
            """)
    @UserMessage("证据：\n{evidence}\n请给出假设列表。")
    String hypothesize(@V("evidence") String evidence, @MemoryId String sessionId);

    /**
     * 假设验证：判定成立/不成立/存疑，输出逐行 JSON 判定（判定数组按行组织，
     * 每行独立可解析）。契约三方：MockDiagnoserGateway.verifyHypotheses 的
     * 剧本输出、HypothesisParser.parseVerdicts（JSON 优先、旧文本兜底）、
     * 前端 parsers.js.parseHypotheses。
     */
    @SystemMessage("""
            你是验证模块。对比补充证据与假设预期，逐条判定 成立/不成立/存疑。
            格式（严格遵守）每行一条 JSON，除 JSON 行外不要输出任何其他文字：
            {"index":假设序号整数,"conclusion":"成立|不成立|存疑","reason":"判定依据"}
            """)
    @UserMessage("假设：\n{hypotheses}\n补充验证证据：\n{moreEvidence}\n请判定。")
    String verifyHypotheses(@V("hypotheses") String hypotheses,
                            @V("moreEvidence") String moreEvidence,
                            @MemoryId String sessionId);

    /** 修复方案：输出含风险标注的动作计划 */
    @SystemMessage("""
            你是处置方案模块。基于确认的根因给修复动作。
            格式： 动作 | 目标 | 风险(LOW|HIGH) | 理由
            重启/扩容类动作一律标 HIGH。给不出可靠动作就输出 NONE。
            """)
    @UserMessage("确认根因：{rootCause}\n请给出处置计划。")
    String makePlan(@V("rootCause") String rootCause, @MemoryId String sessionId);

    /** 复盘报告 */
    @SystemMessage("你是运维复盘撰写者。把时间线、根因、处置、耗时浓缩成不超过10行的报告。")
    @UserMessage("事件 {incidentId} 全过程：{transcript}")
    String retrospect(@V("incidentId") Long incidentId,
                      @V("transcript") String transcript,
                      @MemoryId String sessionId);
}
