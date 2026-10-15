package com.sentryagent;

import com.sentryagent.graph.IncidentState;
import com.sentryagent.llm.DiagnoserGateway;
import com.sentryagent.service.IncidentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回环护栏的图级验收（测试计划 C："verify 回环次数 = max-verify-loops"）。
 * mock 剧本的验证方法恒含 cpu 关键词、首轮判定必"成立"，HTTP 面无法演示回环耗尽——
 * 这里换一个"永不成立"的桩网关，把 sentry.max-verify-loops 压到 2，真实走图：
 * evidence(1) hypothesize(2) verify(3, round=1) -> moreEvidence ->
 * evidence 重取(needsRegen) -> hypothesize 换代 -> verify(round=2) ->
 * round(2) < maxLoops(2) 为假 -> escalate。
 * 断言 verifyRound 恰等于上限、evidenceSeq 换代次数与回环同频（回环真重算而非空转）、
 * 终态 ESCALATED 且无计划无执行——回环耗尽转人工的完整证据链。
 */
@SpringBootTest
@TestPropertySource(properties = "sentry.max-verify-loops=2")
@Import(VerifyLoopGuardTest.NeverEstablishedConfig.class)
class VerifyLoopGuardTest {

    /** 顶掉 mock 剧本网关：判定永远"不成立"，逼图走满回环 */
    @TestConfiguration
    static class NeverEstablishedConfig {
        @Bean
        @Primary // 顶过 LlmConfig 的 mock 剧本网关：同类型两个 Bean，主选测试桩
        DiagnoserGateway neverEstablishedGateway() {
            return new DiagnoserGateway() {
                @Override
                public String gatherEvidence(String service, String alert,
                                             Long incidentId, String sessionId) {
                    return "桩证据[" + alert + "] for " + service;
                }

                @Override
                public String hypothesize(String evidence, String sessionId) {
                    return "1. 假设X | 核对X";
                }

                @Override
                public String verifyHypotheses(String hypotheses, String moreEvidence,
                                               String sessionId) {
                    return "1 | 不成立 | 桩判定始终否定";
                }

                @Override
                public String makePlan(String rootCause, String sessionId) {
                    return "RESTART | x | HIGH | 不该被走到";
                }

                @Override
                public String retrospect(Long incidentId, String transcript, String sessionId) {
                    return "不该被走到";
                }
            };
        }
    }

    @Autowired
    private IncidentService incidentService;

    @Test
    @DisplayName("max-verify-loops=2：回环恰好跑满 2 轮，耗尽后 ESCALATED")
    void loopsCappedAtMax() {
        Map<String, Object> snap = incidentService.createAndRun(
                "judge-service", "loop guard check");
        assertEquals("ESCALATED", String.valueOf(snap.get(IncidentState.OUTCOME)),
                "回环耗尽必须转人工");
        assertEquals(2, snap.get(IncidentState.VERIFY_ROUND),
                "verify 轮数应恰等于 max-verify-loops");
        // 每次 moreEvidence 都真正重新取证换代：证据/假设/判定三条代链同步推进到第 2 代
        assertEquals(2, snap.get(IncidentState.EVIDENCE_SEQ),
                "回环必须驱动证据换代（防空转）：1 首轮 + 1 圈重取");
        assertEquals(2, snap.get(IncidentState.HYPO_SEQ));
        assertEquals(2, snap.get(IncidentState.VERDICT_SEQ));
        // 桩证据文本相同，appender 去重后仍应至少看到首轮+补充两类
        assertTrue(((List<?>) snap.get(IncidentState.EVIDENCES)).size() >= 2,
                "定向取证的补充证据必须进入证据通道");
        // 未产出计划、未执行任何动作
        assertTrueEmpty(String.valueOf(snap.getOrDefault(IncidentState.PLAN, "")));
        assertEquals(List.of(), snap.getOrDefault(IncidentState.ACTIONS, List.of()));
    }

    private static void assertTrueEmpty(String s) {
        org.junit.jupiter.api.Assertions.assertTrue(s == null || s.isEmpty() || "null".equals(s),
                "escalate 前不应有处置计划: " + s);
    }
}
