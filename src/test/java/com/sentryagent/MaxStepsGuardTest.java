package com.sentryagent;

import com.sentryagent.graph.IncidentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局步数护栏的图级验收：把 sentry.max-steps 压到 2，真实走一遍图——
 * evidence(1) -> hypothesize(2) -> verify(3) 后，verifyRouter 的第一分支
 * （steps>=maxSteps）必须无条件压过"判定成立"，事件落 ESCALATED 而不是继续 plan。
 * 这是对计划书 3.2"确定性兜底优先于模型行为"的端到端证明，
 * 和 IncidentRoutersTest 的纯函数分支断言互为两层保险。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sentry.max-steps=2")
class MaxStepsGuardTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private com.sentryagent.service.IncidentService incidentService;

    @Test
    @DisplayName("max-steps=2：判定'成立'也被护栏强制 escalate，outcome=ESCALATED")
    void stepsCapForcesEscalation() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/incidents")
                        .contentType("application/json")
                        .content("{\"service\":\"judge-service\",\"summary\":\"queue 320 > 200\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        Map<String, Object> snap = incidentService.rawSnapshot(1L);
        assertEquals("ESCALATED", String.valueOf(snap.get(IncidentState.OUTCOME)),
                "步数护栏必须优先于判定放行");
        // 护栏触发时未走到 plan：计划通道缺席，报告是"转人工"文案
        assertTrue(!snap.containsKey(IncidentState.PLAN)
                        || String.valueOf(snap.get(IncidentState.PLAN)).isEmpty(),
                "escalate 前不应产出计划");
        String report = String.valueOf(snap.get(IncidentState.REPORT));
        assertTrue(report.contains("已转人工"), report);
        assertTrue(((Integer) snap.get(IncidentState.STEPS)) >= 2);
    }
}
