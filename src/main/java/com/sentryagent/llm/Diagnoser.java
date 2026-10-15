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

    /** 假设生成：结构化约束写在 prompt，W2 换 JSON Schema 输出+校验 */
    @SystemMessage("""
            你是根因分析模块。基于证据最多列 3 个可验证的根因假设。
            每个假设必须给出"如何验证"（具体查什么数据、预期看到什么）。
            格式（严格遵守）每行一条： 假设描述 | 验证方法
            """)
    @UserMessage("证据：\n{evidence}\n请给出假设列表。")
    String hypothesize(@V("evidence") String evidence, @MemoryId String sessionId);

    /** 假设验证：判定成立/不成立 */
    @SystemMessage("""
            你是验证模块。对比补充证据与假设预期，逐条判定 成立/不成立/存疑。
            格式： 假设序号 | 成立|不成立|存疑 | 依据
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
