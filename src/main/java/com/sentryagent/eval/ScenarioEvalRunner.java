package com.sentryagent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentryagent.graph.IncidentState;
import com.sentryagent.service.IncidentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 故障剧本评测 runner（计划书第 8 章"10 剧本×对照基线"的兑现，不足清单第 9 项）。
 *
 * <p>工作方式：加载 classpath:scenarios/scenarios.json 的剧本清单 →
 * 逐条 createAndRun（mock 模式回包确定，路由完全可预言）→ 按 decision 推进
 * → 断言终态与报告关键词 → 聚合通过率/耗时/token 基线 → Report。</p>
 *
 * <p>为什么它能当"对照基线"：mock 网关下每条剧本的图路由是确定性的，
 * 跑批失败 = 路由回归（谁改坏了 verifyRouter/审批闸口/去重逻辑一目了然）；
 * 接真实 LLM 后同一份剧本变成"诊断质量基线"——命中率对比就是 README
 * 叙事里 MTTD/成功率的数据来源。</p>
 *
 * <p>本类无 Spring 依赖（构造器注入 IncidentService），HTTP 出口在
 * DevToolsController（dev-api 开关内）。纯 runner 可直接单测。</p>
 */
@Slf4j
@Component
public class ScenarioEvalRunner {

    /** 单剧本结果关键词断言容差：报告文本里找不到关键词即 fail */
    private static final int DETAIL_MAX = 400;

    private final IncidentService incidentService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScenarioEvalRunner(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    /** 从 classpath 加载剧本清单 */
    public List<ScenarioCase> loadScenarios() {
        try {
            return mapper.readValue(
                    new ClassPathResource("scenarios/scenarios.json").getInputStream(),
                    new TypeReference<List<ScenarioCase>>() {});
        } catch (IOException e) {
            log.error("scenarios.json 加载失败（评测不可用）", e);
            return List.of();
        }
    }

    /** 跑全部剧本并聚合报告 */
    public ScenarioResult.Report runAll() {
        return run(loadScenarios());
    }

    /** 跑指定剧本子集（单测/调试用） */
    public ScenarioResult.Report run(List<ScenarioCase> cases) {
        long start = System.currentTimeMillis();
        List<ScenarioResult> results = new ArrayList<>();
        int passed = 0;
        for (ScenarioCase c : cases) {
            ScenarioResult r = runOne(c);
            results.add(r);
            if (r.pass()) {
                passed++;
            }
        }
        return new ScenarioResult.Report(cases.size(), passed,
                cases.size() - passed, System.currentTimeMillis() - start, results);
    }

    /** 执行单条剧本：create → decision → 断言终态与关键词 */
    ScenarioResult runOne(ScenarioCase c) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> snap = incidentService.createAndRun(c.service(), c.summary());
            long id = ((Number) snap.getOrDefault(IncidentState.INCIDENT_ID, -1)).longValue();
            String outcome = String.valueOf(snap.getOrDefault(IncidentState.OUTCOME, ""));

            if (!"none".equalsIgnoreCase(c.decision())) {
                boolean approve = "approve".equalsIgnoreCase(c.decision());
                snap = incidentService.approve(id, approve);
                outcome = String.valueOf(snap.getOrDefault(IncidentState.OUTCOME, ""));
            }

            List<String> miss = new ArrayList<>(c.expectKeywords());
            String report = String.valueOf(snap.getOrDefault(IncidentState.REPORT, ""))
                    + " " + String.valueOf(snap.getOrDefault(IncidentState.PLAN, ""));
            miss.removeIf(report::contains);

            Object tokens = snap.get(IncidentState.TOTAL_TOKENS);
            long elapsed = System.currentTimeMillis() - start;
            boolean pass = c.expectOutcome().equalsIgnoreCase(outcome) && miss.isEmpty();
            String detail = pass ? "ok" : "outcome=" + outcome + "（期望 "
                    + c.expectOutcome() + "）缺失关键词" + miss;
            log.info("scenario {} -> {} ({}ms, {} tokens)", c.id(), pass ? "PASS" : "FAIL",
                    elapsed, tokens);
            return new ScenarioResult(c.id(), pass, outcome,
                    tokens instanceof Integer i ? i : 0, elapsed, detail);
        } catch (Exception e) {
            return new ScenarioResult(c.id(), false, "EXCEPTION",
                    0, System.currentTimeMillis() - start,
                    truncate(e.getMessage()));
        }
    }

    private static String truncate(String s) {
        String t = s == null ? "null" : s;
        return t.length() > DETAIL_MAX ? t.substring(0, DETAIL_MAX) : t;
    }
}
