package com.sentryagent.eval;

/**
 * 一条故障剧本（计划书第 8 章评测方案的最小实现单元）。
 *
 * <p>mock 模式下网关回包固定（每事件 2 假设、1 成立 1 不成立、确定性处置计划），
 * 所以"剧本 = 输入什么告警 → 走哪个决策分支 → 期待什么终态"完全可预言——
 * 这正是零依赖 mock 设计的评测价值：不需要真实 LLM 就能量化路由正确率。</p>
 *
 * @param id              剧本 ID（如 "S01-queue-backlog"）
 * @param service         被守护服务名
 * @param summary         告警摘要（进 State 的 ALERT 通道）
 * @param decision        create 之后的人工决策：approve / reject / none（停在审批）
 * @param expectOutcome   期望终态（CLOSED / ESCALATED / AWAITING_APPROVAL）
 * @param expectKeywords  复盘报告/动作输出里必须出现的关键词（逐个断言）
 */
public record ScenarioCase(String id, String service, String summary,
                           String decision, String expectOutcome,
                           java.util.List<String> expectKeywords) {
}
