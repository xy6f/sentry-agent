package com.sentryagent.eval;

import com.sentryagent.graph.IncidentState;
import com.sentryagent.service.IncidentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 故障剧本评测 harness 的整图级回归（Phase 3 第 3.3 项）。
 *
 * <p>10 条剧本在 mock 网关下路由完全确定：全绿 = 路由/审批/去重/终态契约
 * 无回归；任何一条 FAIL 都指名是谁改坏了哪条路由（报告带剧本 ID）。
 * 这就是计划书第 8 章对照基线的 mock 形态——接真实 LLM 后同一份剧本
 * 升级为诊断质量基线。</p>
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class ScenarioEvalRunnerTest extends com.sentryagent.support.MySQLContainerBaseTest {

    @Autowired
    private ScenarioEvalRunner runner;

    @Test
    @DisplayName("10 条故障剧本：全数通过（终态 + 关键词双断言）")
    void allScenariosPass() {
        ScenarioResult.Report report = runner.runAll();
        assertEquals(10, report.total(), "剧本清单应有 10 条");
        assertTrue(report.failed() == 0,
                "失败剧本: " + report.summary() + " / 明细: " + report.results());
        assertTrue(report.elapsedMs() < 60_000, "mock 模式跑批应在分钟级以内");
    }

    @Test
    @DisplayName("报告聚合：通过数与逐剧本明细一致，token 基线为 mock 固定值")
    void reportAggregation() {
        ScenarioResult.Report report = runner.run(runner.loadScenarios().subList(0, 2));
        assertEquals(2, report.total());
        assertEquals(report.passed(), report.results().stream()
                .filter(ScenarioResult::pass).count());
        // mock 网关每节点 100 token：approve 闭环 = 700（首轮 600 + 续跑 100）
        report.results().stream()
                .filter(r -> r.finalOutcome().equals("CLOSED"))
                .forEach(r -> assertEquals(700, r.tokens(), "CLOSED 事件 token 基线"));
    }

    @Test
    @DisplayName("单剧本失败定位：expectKeywords 缺失时 detail 指名缺失项")
    void failureDetailPointsAtMissingKeyword() {
        ScenarioCase bad = new ScenarioCase("S-BUG", "judge-service",
                "队列堆积", "approve", "CLOSED", List.of("不存在的关键词XYZ"));
        ScenarioResult r = runner.runOne(bad);
        assertFalse(r.pass());
        assertTrue(r.detail().contains("XYZ"), "detail 必须指名缺失关键词: " + r.detail());
    }
}
