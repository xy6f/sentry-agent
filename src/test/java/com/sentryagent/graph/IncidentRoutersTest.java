package com.sentryagent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 两条条件边路由的全分支单测（纯状态构造，不起 Spring、不跑图）。
 * 路由是"决定故事走向"的判断式，每个分支都必须有独立断言——计划书 3.2
 * "确定性兜底"的可验证形态就落在这里。
 */
class IncidentRoutersTest {

    /** 手搓最小状态：只放路由要读的通道 */
    private static IncidentState state(String verdict, int round, int steps,
                                       String plan, String approval) {
        Map<String, Object> data = new HashMap<>();
        data.put(IncidentState.VERDICT, verdict);
        data.put(IncidentState.VERIFY_ROUND, round);
        data.put(IncidentState.STEPS, steps);
        data.put(IncidentState.PLAN, plan);
        data.put(IncidentState.APPROVAL, approval);
        return new IncidentState(data);
    }

    // ─────────────── verifyRouter：三分支 + steps 护栏优先 ───────────────

    @Test
    @DisplayName("判定有'成立'：去 plan")
    void establishedGoesToPlan() {
        IncidentState s = state("1 | 成立 | cpu=95%", 1, 3, "", "");
        assertEquals("plan", IncidentRouters.verifyRouter(s, 3, 24));
    }

    @Test
    @DisplayName("判定全部'不成立'且圈数未用尽：moreEvidence 回环")
    void notEstablishedLoopsBack() {
        IncidentState s = state("1 | 不成立 | 证据不足", 1, 5, "", "");
        assertEquals("moreEvidence", IncidentRouters.verifyRouter(s, 3, 24));
    }

    @Test
    @DisplayName("判定不成立且圈数已达上限：escalate 转人工")
    void loopsExhaustedEscalates() {
        IncidentState s = state("1 | 不成立 | 证据不足", 3, 5, "", "");
        assertEquals("escalate", IncidentRouters.verifyRouter(s, 3, 24));
    }

    @Test
    @DisplayName("steps 达全局上限：即使判定'成立'也强制 escalate（护栏优先于判定）")
    void stepsCapOverridesEverything() {
        IncidentState established = state("1 | 成立 | cpu=95%", 1, 24, "", "");
        assertEquals("escalate", IncidentRouters.verifyRouter(established, 3, 24));
        IncidentState looping = state("1 | 不成立 | 证据不足", 1, 25, "", "");
        assertEquals("escalate", IncidentRouters.verifyRouter(looping, 3, 24));
    }

    @Test
    @DisplayName("子串陷阱回归：'不成立'不得被误判为'成立'放行")
    void negativeVerdictNeverReadAsPositive() {
        // 早期真实踩过的坑：直接 contains("成立") 会把 "1 | 不成立" 放行去 plan
        IncidentState s = state("1 | 不成立 | 证据不足\n2 | 不成立 | 证据不足", 0, 2, "", "");
        assertEquals("moreEvidence", IncidentRouters.verifyRouter(s, 3, 24));
        // 混合文本：有一条真成立 → 放行
        IncidentState mixed = state("1 | 成立 | cpu=95%\n2 | 不成立 | 无迹象", 0, 2, "", "");
        assertEquals("plan", IncidentRouters.verifyRouter(mixed, 3, 24));
    }

    @Test
    @DisplayName("空判定文本（异常出口）：不成立路径，走回环/耗尽逻辑")
    void emptyVerdictTreatedAsNotEstablished() {
        assertEquals("moreEvidence",
                IncidentRouters.verifyRouter(state("", 1, 2, "", ""), 3, 24));
        assertEquals("escalate",
                IncidentRouters.verifyRouter(state("", 3, 2, "", ""), 3, 24));
    }

    // ─────────────── approvalRouter：NONE 直跳 / 令牌两态 ───────────────

    private static final String REAL_ACTION = "RESTART | judge-worker-1 | HIGH | 重启卡死容器";

    @Test
    @DisplayName("NONE 计划：skipExecute 直跳 retrospect（不依赖令牌）")
    void nonePlanSkipsApproval() {
        IncidentState s = state("x", 1, 4, "NONE", "");
        assertEquals("skipExecute", IncidentRouters.approvalRouter(s));
        // NONE 也不该因为有"令牌残留"而绕去 execute：NONE 判定在最前
        IncidentState withStaleToken = state("x", 1, 4, "NONE", "leftover-token");
        assertEquals("skipExecute", IncidentRouters.approvalRouter(withStaleToken));
    }

    @Test
    @DisplayName("有真实动作但无令牌：pending（存档等审批，绝不带空令牌去 execute）")
    void realActionWithoutTokenWaits() {
        IncidentState s = state("x", 1, 4, REAL_ACTION, "");
        assertEquals("pending", IncidentRouters.approvalRouter(s));
    }

    @Test
    @DisplayName("有真实动作且令牌在手：execute")
    void realActionWithTokenExecutes() {
        IncidentState s = state("x", 1, 4, REAL_ACTION, "tok-123");
        assertEquals("execute", IncidentRouters.approvalRouter(s));
    }

    @Test
    @DisplayName("计划文本全是非法行：isNonePlan=false 且无令牌 → pending（保守闸口）")
    void unparseablePlanAlsoWaits() {
        // 解析不出动作≠NONE：宁可挂在闸口转人工，也不误判成"无需审批直跳报告"
        IncidentState s = state("x", 1, 4, "KILL | x | HIGH | 白名单外", "");
        assertEquals("pending", IncidentRouters.approvalRouter(s));
    }
}
