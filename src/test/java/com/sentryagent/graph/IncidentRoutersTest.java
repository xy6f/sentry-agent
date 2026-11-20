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
 *
 * <p>verifyRouter 现为 4 参签名 (state, maxLoops, maxSteps, tokenBudget)：
 * token 预算护栏与 steps 护栏同款写法（超限无条件 escalate、排最前保证优先级），
 * 每个分支断言都显式传 3/24/50000 这组"常规值"，预算分支单独压到临界点。</p>
 */
class IncidentRoutersTest {

    /** verifyRouter 的常规参数组：3 圈 / 24 步 / 50000 token（与 application.yml 对齐） */
    private static final int MAX_LOOPS = 3;
    private static final int MAX_STEPS = 24;
    private static final int TOKEN_BUDGET = 50000;

    /** 手搓最小状态：只放路由要读的通道 */
    private static IncidentState state(String verdict, int round, int steps,
                                       String plan, String approval) {
        return state(verdict, round, steps, plan, approval, 0);
    }

    /** 带 token 用量的重载：TOTAL_TOKENS 通道值由用例直接指定 */
    private static IncidentState state(String verdict, int round, int steps,
                                       String plan, String approval, int totalTokens) {
        Map<String, Object> data = new HashMap<>();
        data.put(IncidentState.VERDICT, verdict);
        data.put(IncidentState.VERIFY_ROUND, round);
        data.put(IncidentState.STEPS, steps);
        data.put(IncidentState.PLAN, plan);
        data.put(IncidentState.APPROVAL, approval);
        data.put(IncidentState.TOTAL_TOKENS, totalTokens);
        return new IncidentState(data);
    }

    // ─────────────── verifyRouter：分支 + steps/token 双护栏优先 ───────────────

    @Test
    @DisplayName("判定有'成立'：去 plan")
    void establishedGoesToPlan() {
        IncidentState s = state("1 | 成立 | cpu=95%", 1, 3, "", "");
        assertEquals("plan", IncidentRouters.verifyRouter(s, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
    }

    @Test
    @DisplayName("判定全部'不成立'且圈数未用尽：moreEvidence 回环")
    void notEstablishedLoopsBack() {
        IncidentState s = state("1 | 不成立 | 证据不足", 1, 5, "", "");
        assertEquals("moreEvidence", IncidentRouters.verifyRouter(s, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
    }

    @Test
    @DisplayName("判定不成立且圈数已达上限：escalate 转人工")
    void loopsExhaustedEscalates() {
        IncidentState s = state("1 | 不成立 | 证据不足", 3, 5, "", "");
        assertEquals("escalate", IncidentRouters.verifyRouter(s, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
    }

    @Test
    @DisplayName("steps 达全局上限：即使判定'成立'也强制 escalate（护栏优先于判定）")
    void stepsCapOverridesEverything() {
        IncidentState established = state("1 | 成立 | cpu=95%", 1, 24, "", "");
        assertEquals("escalate", IncidentRouters.verifyRouter(established, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
        IncidentState looping = state("1 | 不成立 | 证据不足", 1, 25, "", "");
        assertEquals("escalate", IncidentRouters.verifyRouter(looping, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
    }

    @Test
    @DisplayName("token 达预算：即使判定'成立'也强制 escalate（成本护栏优先于判定）")
    void tokenBudgetOverridesEverything() {
        // 恰到预算（>= 语义）：判定成立也拦下——与 steps 护栏同款边界
        IncidentState atBudget = state("1 | 成立 | cpu=95%", 1, 3, "", "", 50000);
        assertEquals("escalate", IncidentRouters.verifyRouter(atBudget, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
        // 超预算：不成立路径同样拦下（回环也不许再烧钱）
        IncidentState overBudget = state("1 | 不成立 | 证据不足", 1, 3, "", "", 50001);
        assertEquals("escalate", IncidentRouters.verifyRouter(overBudget, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
    }

    @Test
    @DisplayName("预算临界之下（49999）：判定成立正常放行去 plan")
    void justUnderBudgetGoesToPlan() {
        IncidentState s = state("1 | 成立 | cpu=95%", 1, 3, "", "", 49999);
        assertEquals("plan", IncidentRouters.verifyRouter(s, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
    }

    @Test
    @DisplayName("steps 护栏优先于 token 护栏：双双超限时也只 escalate 一次（语义等价）")
    void stepsGuardCheckedBeforeTokenGuard() {
        IncidentState both = state("1 | 成立 | cpu=95%", 1, 30, "", "", 60000);
        assertEquals("escalate", IncidentRouters.verifyRouter(both, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
    }

    @Test
    @DisplayName("子串陷阱回归：'不成立'不得被误判为'成立'放行")
    void negativeVerdictNeverReadAsPositive() {
        // 早期真实踩过的坑：直接 contains("成立") 会把 "1 | 不成立" 放行去 plan
        IncidentState s = state("1 | 不成立 | 证据不足\n2 | 不成立 | 证据不足", 0, 2, "", "");
        assertEquals("moreEvidence", IncidentRouters.verifyRouter(s, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
        // 混合文本：有一条真成立 → 放行
        IncidentState mixed = state("1 | 成立 | cpu=95%\n2 | 不成立 | 无迹象", 0, 2, "", "");
        assertEquals("plan", IncidentRouters.verifyRouter(mixed, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
    }

    @Test
    @DisplayName("JSON 判定（不足清单第 4 项）：成立放行去 plan，全不成立回环")
    void jsonVerdictRoutesByStructuredParse() {
        // 结构化判定通道（逐行 JSON）的正路径：conclusion=成立 → plan
        IncidentState established = state(
                "{\"index\":1,\"conclusion\":\"成立\",\"reason\":\"cpu=95% 且容器超限\"}", 1, 3, "", "");
        assertEquals("plan", IncidentRouters.verifyRouter(established, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));

        // JSON 时代的同款子串陷阱回归：全"不成立"的 JSON 行若仍用旧
        // replace("不成立","").contains("成立") 子串推断，替换后恰好剩下
        // "成立"字样会被误放行——路由必须走结构化白名单匹配
        IncidentState allRejected = state(
                "{\"index\":1,\"conclusion\":\"不成立\",\"reason\":\"证据不足\"}\n"
                        + "{\"index\":2,\"conclusion\":\"不成立\",\"reason\":\"证据不足\"}", 0, 2, "", "");
        assertEquals("moreEvidence",
                IncidentRouters.verifyRouter(allRejected, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));

        // 存疑：不算成立 → 回环（存疑不是放行依据，宁可多取证一圈）
        IncidentState uncertain = state(
                "{\"index\":1,\"conclusion\":\"存疑\",\"reason\":\"证据相互矛盾\"}", 1, 2, "", "");
        assertEquals("moreEvidence",
                IncidentRouters.verifyRouter(uncertain, MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
    }

    @Test
    @DisplayName("空判定文本（异常出口）：不成立路径，走回环/耗尽逻辑")
    void emptyVerdictTreatedAsNotEstablished() {
        assertEquals("moreEvidence",
                IncidentRouters.verifyRouter(state("", 1, 2, "", ""), MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
        assertEquals("escalate",
                IncidentRouters.verifyRouter(state("", 3, 2, "", ""), MAX_LOOPS, MAX_STEPS, TOKEN_BUDGET));
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
