package com.sentryagent;

import com.sentryagent.graph.IncidentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * token 预算护栏的图级验收（sentry.token-budget-per-incident）：
 * 把预算压到 50（< mock 单次调用的 100），真实走一遍图——
 * evidence(100) → hypothesize(200) → verify(二次取证+判定 = 500) 后，
 * verifyRouter 的 token 分支（totalTokens >= budget）必须无条件压过"判定成立"，
 * 事件落 ESCALATED 而不是继续 plan。与 MaxStepsGuardTest 同款布局：
 * 步数护栏与成本护栏两道确定性兜底各自有一个图级红灯开关，
 * 和 IncidentRoutersTest 的纯函数分支断言互为两层保险。
 * （Spring 测试上下文按配置缓存：本类独立上下文，事件 id 从 1 重新计数。）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sentry.token-budget-per-incident=50")
class TokenBudgetGuardTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private com.sentryagent.service.IncidentService incidentService;

    @Test
    @DisplayName("budget=50：判定'成立'也被成本护栏强制 escalate，outcome=ESCALATED")
    void tokenBudgetForcesEscalation() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/incidents")
                        .contentType("application/json")
                        .content("{\"service\":\"judge-service\",\"summary\":\"budget guard check\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        Map<String, Object> snap = incidentService.rawSnapshot(1L);
        assertEquals("ESCALATED", String.valueOf(snap.get(IncidentState.OUTCOME)),
                "token 预算护栏必须优先于判定放行（与 steps 护栏同款语义）");
        // 账面：evidence(100) + hypothesize(100) + verify(二次取证2×100 + 判定100=400)
        // = 5 次调用照实入账；护栏在 verify 之后的条件边上拦下，plan 不得产出
        assertEquals(500, snap.get(IncidentState.TOTAL_TOKENS),
                "escalate 前已发生的推理调用照实入账（取证/假设/二次取证×2/判定）");
        // 护栏触发时未走到 plan：计划通道缺席；报告是"转人工"文案并带 token 账目
        assertTrue(String.valueOf(snap.getOrDefault(IncidentState.PLAN, "")).isEmpty(),
                "超预算 escalate 前不应产出计划");
        String report = String.valueOf(snap.get(IncidentState.REPORT));
        assertTrue(report.contains("已转人工"), report);
        assertTrue(report.contains("累计token=500"), report);
    }
}
