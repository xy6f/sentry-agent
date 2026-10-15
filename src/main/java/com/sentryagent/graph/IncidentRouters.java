package com.sentryagent.graph;

/**
 * 图上两条条件边的路由决策，抽成纯静态类——路由是整张图里"决定故事走向"的
 * 判断式，它必须能脱离 Spring、脱离模型被逐分支单测（计划书 3.2"确定性兜底"
 * 的可验证形态）。工厂装配时只做一行委托。
 */
public final class IncidentRouters {

    private IncidentRouters() {
        // 工具类禁实例化
    }

    /**
     * verify 之后的三/四分支路由：
     * <ol>
     *   <li>steps 已达 sentry.max-steps：无论判定如何，强制 escalate——
     *       全局步数护栏（计划书"节点总数×2 硬上限"的落地），排最前保证优先级；</li>
     *   <li>判定含"成立"：去 plan。注意必须先剔除"不成立"再找"成立"——
     *       否则 "1 | 不成立" 这种文本会因包含子串"成立"被误放行（真实踩过的坑）；</li>
     *   <li>不成立且回环未超 sentry.max-verify-loops：moreEvidence，回 evidence
     *       重新取证再假设——验证方法驱动的二次取证让这一圈真正产生新信息；</li>
     *   <li>圈数耗尽：escalate 转人工。</li>
     * </ol>
     */
    public static String verifyRouter(IncidentState s, int maxLoops, int maxSteps) {
        if (s.steps() >= maxSteps) {
            return "escalate";
        }
        String verdictWithoutNegative = s.verdict().replace("不成立", "");
        if (verdictWithoutNegative.contains("成立")) {
            return "plan";
        }
        if (s.round() < maxLoops) {
            return "moreEvidence";
        }
        return "escalate";
    }

    /**
     * awaitApproval 闸口之后的路由：
     * <ol>
     *   <li>NONE 计划：没有任何写操作要审批，直接 skipExecute 去 retrospect 出报告
     *       （人工介入的建议写在报告里，而不是把事件挂死在闸口）；</li>
     *   <li>有真实动作但令牌为空：pending，快照存档后本轮 run 结束，
     *       等审批接口签发令牌再重放；</li>
     *   <li>令牌在手：execute，逐动作走工具+一次性令牌护栏。</li>
     * </ol>
     * 顺序上"先判 NONE 再判令牌"：NONE 直跳不依赖令牌。
     */
    public static String approvalRouter(IncidentState s) {
        if (PlanParser.isNonePlan(s.plan())) {
            return "skipExecute";
        }
        if (s.approval().isEmpty()) {
            return "pending";
        }
        return "execute";
    }
}
