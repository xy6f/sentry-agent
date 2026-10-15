package com.sentryagent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanParser 纯单测（不碰 Spring、不碰模型）：
 * 计划文本是 LLM 出口进确定性世界的边界，合法/非法两条路都必须有断言钉死。
 */
class PlanParserTest {

    @Test
    @DisplayName("合法四段行：解析出 type 大写、各段 trim")
    void parsesValidLines() {
        String plan = """
                restart | judge-worker-1 | high | 重启卡死容器
                SCALE | judge-worker 4->6 | LOW | 临时扩容
                """;
        List<PlanParser.PlanAction> actions = PlanParser.parse(plan);
        assertEquals(2, actions.size());
        assertEquals("RESTART", actions.get(0).type());
        assertEquals("judge-worker-1", actions.get(0).target());
        assertEquals("HIGH", actions.get(0).risk());
        assertEquals("重启卡死容器", actions.get(0).reason());
        // 目标允许含空格与 "->"（SCALE 的目标 "judge-worker 4->6"），只要段数是 4
        assertEquals(2, actions.get(1).target().split(" ").length);
        assertEquals("LOW", actions.get(1).risk());
    }

    @Test
    @DisplayName("非法行一律丢弃：段数不对/动作不在白名单/风险非法/目标为空")
    void dropsInvalidLines() {
        String plan = """
                RESTART judge-worker-1 HIGH 没有竖线
                RESTART | x | HIGH | ok 这一行合法
                KILL | x | HIGH | 动作不在白名单
                RESTART | x | MEDIUM | 风险非法
                RESTART |   | HIGH | 目标为空
                | x | HIGH | 动作为空
                """;
        List<PlanParser.PlanAction> actions = PlanParser.parse(plan);
        assertEquals(1, actions.size());
        assertEquals("RESTART", actions.get(0).type());
        assertEquals("x", actions.get(0).target());
    }

    @Test
    @DisplayName("null/空白输入得到空列表，不抛异常")
    void tolerantToNullAndBlank() {
        assertTrue(PlanParser.parse(null).isEmpty());
        assertTrue(PlanParser.parse("   \n\n ").isEmpty());
    }

    @Test
    @DisplayName("isNonePlan：裸 NONE、全 NONE 行为 true；混入真实动作或空文本为 false")
    void nonePlanDetection() {
        assertTrue(PlanParser.isNonePlan("NONE"));
        assertTrue(PlanParser.isNonePlan("  none \n"));
        assertTrue(PlanParser.isNonePlan("NONE | judge-service | LOW | 建议人工介入"));
        assertFalse(PlanParser.isNonePlan("RESTART | judge-worker-1 | HIGH | 重启"));
        assertFalse(PlanParser.isNonePlan("RESTART | x | HIGH | y\nNONE | z | LOW | w")); // 混合仍需审批
        assertFalse(PlanParser.isNonePlan(""));
        assertFalse(PlanParser.isNonePlan(null));
        assertFalse(PlanParser.isNonePlan("KILL | x | HIGH | 全非法行解析后为空")); // 解析空≠NONE，保守不直跳
    }
}
