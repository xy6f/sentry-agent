package com.sentryagent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次性审批令牌护栏单测（计划书 3.2"安全"的验收面）：
 * 写操作工具必须"框架层拒绝"——无令牌 REFUSED，令牌一次性（用第二次也 REFUSED），
 * 且 REFUSED 的返回值里绝不出现 OK 字样。这里不跑图、不起 Spring，
 * 直接构造 EvidenceTools（@Value 字段用 ReflectionTestUtils 灌测试值）。
 */
class ApprovalTokenGuardTest {

    private ApprovalRegistry registry;
    private EvidenceTools tools;

    @BeforeEach
    void setUp() {
        registry = new ApprovalRegistry();
        tools = new EvidenceTools(registry);
        // 本测试不触网：targetBaseUrl/logsDir 给占位值即可，写工具不读它们
        ReflectionTestUtils.setField(tools, "targetBaseUrl", "http://127.0.0.1:1");
        ReflectionTestUtils.setField(tools, "logsDir", "./target/unused-logs");
        ReflectionTestUtils.setField(tools, "logsTailLines", 10);
    }

    @Test
    @DisplayName("无令牌（null/空串）调 restartContainer：必须 REFUSED")
    void restartWithoutTokenRefused() {
        assertTrue(tools.restartContainer("judge-worker-1", null, 1L).startsWith("REFUSED"));
        assertTrue(tools.restartContainer("judge-worker-1", "", 1L).startsWith("REFUSED"));
        // 模型"幻觉"出来的假令牌同样撞墙
        assertTrue(tools.restartContainer("judge-worker-1", "made-up-token", 1L)
                .startsWith("REFUSED"));
    }

    @Test
    @DisplayName("令牌一次性：第一次 OK、第二次 REFUSED（防重放）")
    void tokenIsOneShot() {
        String token = registry.issue(7L);
        String first = tools.restartContainer("judge-worker-1", token, 7L);
        assertTrue(first.startsWith("OK:"), "首用应放行: " + first);
        String second = tools.restartContainer("judge-worker-1", token, 7L);
        assertTrue(second.startsWith("REFUSED"), "重放必须拒绝: " + second);
    }

    @Test
    @DisplayName("令牌绑定事件：A 事件的令牌去执行 B 事件 → REFUSED，且令牌即烧毁（fail-safe）")
    void tokenBoundToIncident() {
        String token = registry.issue(1L);
        // consume 是"先弹出再比对"：错用一次令牌同样作废（宁可多毁一枚，不可多放一行）
        assertTrue(tools.scaleService("judge-service", 6, token, 2L).startsWith("REFUSED"));
        assertTrue(tools.scaleService("judge-service", 6, token, 1L).startsWith("REFUSED"),
                "被错用烧毁后，正确事件也不能再用同一令牌");
    }

    @Test
    @DisplayName("REFUSED 语义完整：文案含'不得重试'指令，供模型停止纠缠")
    void refusalCarriesInstruction() {
        String refused = tools.restartContainer("x", "bad", 9L);
        assertTrue(refused.contains("不得重试"));
        assertTrue(!refused.contains("OK"), "拒绝文案不得混入成功标记");
    }

    @Test
    @DisplayName("scaleService 与 restartContainer 走同一道护栏")
    void scaleSharesGuard() {
        assertTrue(tools.scaleService("judge-service", 6, null, 1L).startsWith("REFUSED"));
        String token = registry.issue(3L);
        assertEquals("OK: scale judge-service workers -> 6",
                tools.scaleService("judge-service", 6, token, 3L));
        assertTrue(tools.scaleService("judge-service", 6, token, 3L).startsWith("REFUSED"));
    }
}
