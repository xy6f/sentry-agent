package com.sentryagent.graph;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 假设列表解析器（纯类），契约与 Diagnoser.hypothesize 的提示词一致：
 * 每行一条 {@code 假设描述 | 验证方法}。
 *
 * <p>与 PlanParser 的"非法行直接丢弃"不同，这里采取宽容策略：
 * 没有竖线的行仍算一条假设（只是验证方法缺失），补一个通用兜底验证方法
 * "重新核对日志尾部与关键指标"——假设少解读一条可能漏根因，
 * 而计划动作误执行会炸生产，两者容错方向相反，这是刻意的不对称。</p>
 *
 * <p>verify 节点用 {@link #verifyMethods(String)} 把验证方法抽出来作为
 * 二次定向取证的参数（计划书"按假设再取证"的代码化）。</p>
 */
@Slf4j
public final class HypothesisParser {

    /** 缺验证方法时的兜底动作，保证回环的二次取证永远有真实参数 */
    public static final String DEFAULT_VERIFY_METHOD = "重新核对日志尾部与关键指标";

    /** 一条假设：描述 + 其专属的验证方法 */
    public record HypothesisLine(String cause, String verifyMethod) {
    }

    private HypothesisParser() {
        // 工具类禁实例化
    }

    public static List<HypothesisLine> parse(String hypothesesText) {
        List<HypothesisLine> out = new ArrayList<>();
        if (hypothesesText == null || hypothesesText.isBlank()) {
            return out;
        }
        for (String raw : hypothesesText.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            int sep = line.indexOf('|');
            if (sep < 0) {
                log.debug("假设行无验证方法，使用兜底: {}", line);
                out.add(new HypothesisLine(line, DEFAULT_VERIFY_METHOD));
            } else {
                String cause = line.substring(0, sep).trim();
                String method = line.substring(sep + 1).trim();
                if (cause.isEmpty()) {
                    continue; // 没有假设描述的"纯验证方法行"无意义，跳过
                }
                out.add(new HypothesisLine(cause,
                        method.isEmpty() ? DEFAULT_VERIFY_METHOD : method));
            }
        }
        return out;
    }

    /** 抽取全部验证方法（去空、去重、保持顺序），供 verify 节点逐条再取证 */
    public static List<String> verifyMethods(String hypothesesText) {
        return parse(hypothesesText).stream()
                .map(HypothesisLine::verifyMethod)
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .toList();
    }
}
