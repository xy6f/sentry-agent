package com.sentryagent.graph;

/**
 * 一条结构化验证判定（序号/结论/依据）——verifyHypotheses 环节的产出契约
 * （不足清单第 4 项）。
 *
 * <p>字段契约三方共享：mock 网关逐行吐 JSON
 * {@code {"index":1,"conclusion":"成立","reason":"cpu=95% ..."}}；
 * HypothesisParser.parseVerdicts（JSON 优先、旧文本 "序号 | 结论 | 依据" 兜底）解析；
 * 前端 parsers.js 的 parseHypotheses 按同一 JSON 行格式把判定挂到对应假设卡上。</p>
 *
 * <p>设计要点：conclusion 保留中文领域词（成立/不成立/存疑）而不是英文枚举——
 * 它要直接进 prompt、进判定卡、进复盘报告，中英互译徒增一层漂移面；
 * 需要"机器语义"时用 {@link #held()} 与 {@link #toStatus()}。</p>
 */
public record Verdict(int index, String conclusion, String reason) {

    /** 结论白名单：严格全字匹配的判词（旧实现 replace+contains 的子串陷阱就此根除） */
    public static final String HELD = "成立";
    public static final String REJECTED = "不成立";
    public static final String UNCERTAIN = "存疑";

    /** 结论是否"成立"（严格全字匹配，绝不子串推断） */
    public boolean held() {
        return HELD.equals(conclusion);
    }

    /**
     * 判定结论到假设状态的映射（回环闭环的结构化表达）：
     * 成立到 CONFIRMED、不成立到 REJECTED、存疑到 UNCERTAIN、未知词回落 PENDING。
     */
    public Hypothesis.Status toStatus() {
        if (held()) {
            return Hypothesis.Status.CONFIRMED;
        }
        if (REJECTED.equals(conclusion)) {
            return Hypothesis.Status.REJECTED;
        }
        if (UNCERTAIN.equals(conclusion)) {
            return Hypothesis.Status.UNCERTAIN;
        }
        return Hypothesis.Status.PENDING;
    }

    /**
     * 结论词归一：LLM 万一按英文状态词输出（CONFIRMED/REJECTED/UNCERTAIN，
     * 与 Hypothesis.Status 同名同义）时映射回中文判词；识别不了的原文照回——
     * 上层解析器按白名单决定去留。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return switch (raw.trim()) {
            case HELD, "CONFIRMED" -> HELD;
            case REJECTED, "REJECTED" -> REJECTED;
            case UNCERTAIN, "UNCERTAIN" -> UNCERTAIN;
            default -> raw.trim();
        };
    }
}
