package com.sentryagent.eval;

import java.util.List;

/**
 * 单条剧本的执行结果（评测报告的行）。
 *
 * @param id        剧本 ID
 * @param pass      是否达成期望终态且关键词全命中
 * @param finalOutcome 实际终态
 * @param tokens    该事件累计 token（mock 固定值，用于成本基线）
 * @param elapsedMs 耗时毫秒
 * @param detail    失败原因/关键词命中间差（pass 时为 "ok"）
 */
public record ScenarioResult(String id, boolean pass, String finalOutcome,
                             int tokens, long elapsedMs, String detail) {

    /** 聚合报告：10 剧本跑批的顶层产出 */
    public record Report(int total, int passed, int failed, long elapsedMs,
                         List<ScenarioResult> results) {

        public String summary() {
            return "scenarios: " + passed + "/" + total + " passed in " + elapsedMs + "ms"
                    + (failed > 0 ? " — FAILED: " + results.stream()
                            .filter(r -> !r.pass()).map(ScenarioResult::id).toList() : "");
        }
    }
}
