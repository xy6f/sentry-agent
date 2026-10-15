package com.sentryagent.graph;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 处置计划解析器（纯类：静态方法、零 Spring、零 IO——图上"模型输出进确定性世界"的收口）。
 *
 * <p>契约（与 Diagnoser/MockDiagnoserGateway 的提示词格式逐字对齐）：每行一条动作，
 * 竖线分隔四段：{@code 动作 | 目标 | 风险 | 理由}。</p>
 * <ul>
 *   <li>动作类型白名单：RESTART / SCALE / ROLLBACK / NONE（大小写不敏感，入库统一大写）；</li>
 *   <li>风险白名单：LOW / HIGH；</li>
 *   <li>非法行（段数不对、动作或风险不在白名单）一律丢弃并 log.warn——
 *       宁缺毋滥：解析不出的动作绝不能被执行，这是"LLM 输出不可信任"的落点。</li>
 * </ul>
 */
@Slf4j
public final class PlanParser {

    /** 可执行的白名单动作（NONE=不处置；ROLLBACK 暂无工具，execute 节点会落"无人可执行"结果） */
    public static final Set<String> ACTION_TYPES = Set.of("RESTART", "SCALE", "ROLLBACK", "NONE");
    public static final Set<String> RISK_LEVELS = Set.of("LOW", "HIGH");

    /** 一条已解析的处置动作。type 大写、target/risk/reason 已 trim */
    public record PlanAction(String type, String target, String risk, String reason) {
        /** 是否"无动作"占位（NONE 不参与执行） */
        public boolean isNone() {
            return "NONE".equals(type);
        }
        /** 是否需要审批令牌才能执行：白名单里除 NONE/ROLLBACK 外的写操作都要令牌护栏 */
        public boolean requiresApproval() {
            return "RESTART".equals(type) || "SCALE".equals(type);
        }
    }

    private PlanParser() {
        // 工具类禁实例化
    }

    /**
     * 解析计划文本为结构化动作列表。
     *
     * @param planText LLM/网关产出的原始多行文本，允许为 null
     * @return 合法动作列表（可能为空——"全非法"与"NONE"都视为无可执行动作）
     */
    public static List<PlanAction> parse(String planText) {
        List<PlanAction> actions = new ArrayList<>();
        if (planText == null || planText.isBlank()) {
            return actions;
        }
        // 兼容整份输出只写了一个 "NONE"（无竖线）的极端情况：按契约行处理，四段缺失则被丢弃，
        // 但 isNonePlan 会对纯 NONE 文本单独兜底
        for (String raw : planText.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 4) {
                log.warn("PlanParser 丢弃非法行（段数 {}!=4）: {}", parts.length, line);
                continue;
            }
            String type = parts[0].trim().toUpperCase();
            String target = parts[1].trim();
            String risk = parts[2].trim().toUpperCase();
            String reason = parts[3].trim();
            if (!ACTION_TYPES.contains(type)) {
                log.warn("PlanParser 丢弃非法行（动作不在白名单）: {}", line);
                continue;
            }
            if (!RISK_LEVELS.contains(risk)) {
                log.warn("PlanParser 丢弃非法行（风险标注非法）: {}", line);
                continue;
            }
            if (target.isEmpty()) {
                log.warn("PlanParser 丢弃非法行（目标为空）: {}", line);
                continue;
            }
            actions.add(new PlanAction(type, target, risk, reason));
        }
        return actions;
    }

    /**
     * 是否"无处置计划"：文本 trim 后恰为 NONE，或解析结果非空且全部是 NONE 动作。
     * 供 approvalRouter 判定"直跳 retrospect"（计划书：NONE 计划无需审批）。
     */
    public static boolean isNonePlan(String planText) {
        if (planText == null) {
            return false;
        }
        if (planText.trim().equalsIgnoreCase("NONE")) {
            return true;
        }
        List<PlanAction> actions = parse(planText);
        return !actions.isEmpty() && actions.stream().allMatch(PlanAction::isNone);
    }
}
