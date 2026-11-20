package com.sentryagent.graph;

/**
 * 一条结构化根因假设（不足清单第 4 项：假设-验证回环从"形式回环"升级为
 * "结构化契约"的核心载体之一）。
 *
 * <p>字段契约三方共享（改任何一处都要让 GraphDataContractTest 变红）：</p>
 * <ul>
 *   <li>产出侧：mock 网关 hypothesize 每行吐一个 JSON 对象
 *       {@code {"cause":...,"confidence":85,"verifyAction":"getMetrics ..."}}，
 *       生产侧 Diagnoser.hypothesize 的 @SystemMessage 同步要求产 JSON
 *       （LLM 结构化输出的前端契约升级位）；</li>
 *   <li>解析侧：HypothesisParser.parse（JSON 优先、旧文本 "假设 | 验证方法"
 *       兜底兼容）；</li>
 *   <li>消费侧：verify 节点拿 verifyAction 做定向补充取证（对症下药），
 *       前端 parsers.js 的 parseHypotheses 按同一 JSON 行格式渲染假设卡。</li>
 * </ul>
 *
 * <p>record 的不可变语义是刻意的：假设一旦生成就是"当时的判断"，后续演化
 * （PENDING 到判定结果）体现为新的 Verdict 记录而非原地篡改——复盘审计的
 * 时间线更干净；需要"回填判定"时用 withStatus 派生副本。</p>
 */
public record Hypothesis(String cause, int confidence, String verifyAction, Status status) {

    /**
     * 判定状态机：解析态恒 PENDING；verify 环节按判定结论映射
     * （成立到 CONFIRMED、不成立到 REJECTED、存疑到 UNCERTAIN，映射见 Verdict.toStatus）。
     */
    public enum Status { PENDING, CONFIRMED, REJECTED, UNCERTAIN }

    /** 旧文本格式（"假设 | 验证方法"）不含置信度信息时的中性默认值：50 表示"未声明"而非低可信 */
    public static final int DEFAULT_CONFIDENCE = 50;

    /** 带新 status 的派生副本（record 不可变，"修改"即拷贝） */
    public Hypothesis withStatus(Status newStatus) {
        return new Hypothesis(cause, confidence, verifyAction, newStatus);
    }
}
