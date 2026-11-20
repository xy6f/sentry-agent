package com.sentryagent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 假设/判定的结构化解析器（不足清单第 4 项的核心联动面，纯类）。
 *
 * <p>两级解析、渐进迁移（后端 HypothesisParser 与前端 parsers.js 保持同一解析次序）：
 * 每行先按 JSON 解析——</p>
 * <ul>
 *   <li>假设行：{@code {"cause":...,"confidence":85,"verifyAction":"getMetrics ..."}}；</li>
 *   <li>判定行：{@code {"index":1,"conclusion":"成立","reason":"cpu=95% ..."}}。</li>
 * </ul>
 * <p>JSON 解析失败再回退旧文本格式——假设行 "假设描述 | 验证方法"、
 * 判定行 "序号 | 成立/不成立/存疑 | 依据"。mock 网关已升级为 JSON 产出，
 * 生产 LLM 的 prompt 同步要求产 JSON；历史剧本与老版本产出靠旧格式兜底
 * 继续流转，迁移逐行生效互不等待（GraphDataContractTest 有专门的兼容用例）。</p>
 *
 * <p>容错方向（与 PlanParser "非法行直接丢弃" 同一条纪律，与旧版宽容策略的
 * 刻意不对称保留在旧文本分支）：JSON 行是 LLM 结构化输出的硬契约，格式残缺
 * （非法 JSON / 缺 cause / 缺 index）一律丢弃并记日志——一条残缺假设混进回环
 * 会在判定对齐时产生幽灵序号；而旧文本行保留旧版的宽容策略（无竖线行仍算
 * 一条假设并补兜底验证动作），因为假设少解读一条可能漏根因。</p>
 *
 * <p>verify 节点消费 {@link #parse(String)} 的结果逐假设调
 * DiagnoserGateway.gatherVerifyEvidence 做定向补充取证（计划书"按假设再取证"
 * 的代码化，不足清单第 4 项）；plan 节点用 {@link #confirmedCauses} 把
 * "判定成立"的根因结构化提取出来（判定 JSON 的字段名/引号不再漏进计划 prompt）。</p>
 */
@Slf4j
public final class HypothesisParser {

    /** 缺验证动作时的兜底动作，保证定向补充取证永远有真实参数 */
    public static final String DEFAULT_VERIFY_METHOD = "重新核对日志尾部与关键指标";

    /** 旧文本判定行的回退正则：序号 | 结论(成立/不成立/存疑) | 依据 */
    private static final Pattern LEGACY_VERDICT =
            Pattern.compile("^(\\d+)[.、]?\\s*\\|\\s*(成立|不成立|存疑)\\s*\\|\\s*(.*)$");

    /** 旧文本假设行首的序号前缀（"1. " / "2、"），提取 cause 时剥掉 */
    private static final Pattern LEGACY_HYPO_INDEX = Pattern.compile("^\\d+[.、]\\s*");

    private static final ObjectMapper OM = new ObjectMapper();

    private HypothesisParser() {
        // 工具类禁实例化
    }

    /**
     * 解析假设列表：JSON 行优先，旧文本行兜底；非法 JSON 行丢弃、
     * 缺 cause 的结构性残缺行同样丢弃（丢一条好过放一条幽灵假设进回环）。
     */
    public static List<Hypothesis> parse(String hypothesesText) {
        List<Hypothesis> out = new ArrayList<>();
        if (hypothesesText == null || hypothesesText.isBlank()) {
            return out;
        }
        for (String raw : hypothesesText.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("{")) {
                Hypothesis h = hypothesisFromJson(line);
                if (h != null) {
                    out.add(h);
                }
                continue;
            }
            // 旧文本回退："N. 假设 | 验证方法"；无竖线行宽容为纯假设（补兜底验证动作）
            int sep = line.indexOf('|');
            if (sep < 0) {
                log.debug("假设行无验证方法，使用兜底: {}", line);
                out.add(new Hypothesis(line, Hypothesis.DEFAULT_CONFIDENCE,
                        DEFAULT_VERIFY_METHOD, Hypothesis.Status.PENDING));
            } else {
                String cause = stripLegacyIndex(line.substring(0, sep).trim());
                String method = line.substring(sep + 1).trim();
                if (cause.isEmpty()) {
                    continue; // 没有假设描述的"纯验证方法行"无意义，跳过
                }
                out.add(new Hypothesis(cause, Hypothesis.DEFAULT_CONFIDENCE,
                        method.isEmpty() ? DEFAULT_VERIFY_METHOD : method,
                        Hypothesis.Status.PENDING));
            }
        }
        return out;
    }

    /**
     * 解析判定列表：JSON 行优先，旧文本行兜底；非法 JSON 行与缺 index/conclusion
     * 的残缺行丢弃。返回的判定序号应与假设行序一致（前端按序号挂卡）。
     */
    public static List<Verdict> parseVerdicts(String verdictText) {
        List<Verdict> out = new ArrayList<>();
        if (verdictText == null || verdictText.isBlank()) {
            return out;
        }
        for (String raw : verdictText.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("{")) {
                Verdict v = verdictFromJson(line);
                if (v != null) {
                    out.add(v);
                }
                continue;
            }
            Matcher m = LEGACY_VERDICT.matcher(line);
            if (m.matches()) {
                out.add(new Verdict(Integer.parseInt(m.group(1)), m.group(2), m.group(3).trim()));
            } else {
                log.debug("判定行不匹配任何已知格式，丢弃: {}", line);
            }
        }
        return out;
    }

    /** 判定文本里是否"有假设成立"（结构化解析后严格匹配"成立"，绝不子串推断） */
    public static boolean hasEstablished(String verdictText) {
        return parseVerdicts(verdictText).stream().anyMatch(Verdict::held);
    }

    /**
     * 结构化提取"判定成立"的假设 cause（plan 节点喂 makePlan 的根因文本）：
     * 判定序号即假设行序，序号越界（幽灵判定）安全跳过。
     * 返回空串表示无成立根因（makePlan 契约：空根因产出 NONE 计划）。
     */
    public static String confirmedCauses(String hypothesesText, String verdictText) {
        List<Hypothesis> hyps = parse(hypothesesText);
        StringBuilder sb = new StringBuilder();
        for (Verdict v : parseVerdicts(verdictText)) {
            int idx = v.index();
            if (v.held() && idx >= 1 && idx <= hyps.size()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(hyps.get(idx - 1).cause());
            }
        }
        return sb.toString();
    }

    /** 抽取全部验证动作（去空、去重、保持顺序），供旧调用点/测试使用 */
    public static List<String> verifyActions(String hypothesesText) {
        return parse(hypothesesText).stream()
                .map(Hypothesis::verifyAction)
                .filter(a -> a != null && !a.isBlank())
                .distinct()
                .toList();
    }

    /** 兼容别名：W2 时代"验证方法"的叫法（旧调用点与既有用例沿用） */
    public static List<String> verifyMethods(String hypothesesText) {
        return verifyActions(hypothesesText);
    }

    /** 单行假设 JSON 的解析：缺 cause 即结构性残缺，丢弃 */
    private static Hypothesis hypothesisFromJson(String line) {
        try {
            JsonNode n = OM.readTree(line);
            String cause = n.path("cause").asText("").trim();
            if (cause.isEmpty()) {
                log.warn("丢弃结构性残缺的假设 JSON 行（缺 cause）: {}", line);
                return null;
            }
            int confidence = n.path("confidence").asInt(Hypothesis.DEFAULT_CONFIDENCE);
            if (confidence < 0) {
                confidence = 0;
            }
            if (confidence > 100) {
                confidence = 100;
            }
            String action = n.path("verifyAction").asText("").trim();
            return new Hypothesis(cause, confidence,
                    action.isEmpty() ? DEFAULT_VERIFY_METHOD : action,
                    Hypothesis.Status.PENDING);
        } catch (Exception e) {
            log.warn("丢弃非法 JSON 假设行（{}）: {}", e.getMessage(), line);
            return null;
        }
    }

    /** 单行判定 JSON 的解析：缺 index/conclusion 即结构性残缺，丢弃 */
    private static Verdict verdictFromJson(String line) {
        try {
            JsonNode n = OM.readTree(line);
            JsonNode idx = n.get("index");
            if (idx == null || !idx.isNumber()) {
                log.warn("丢弃结构性残缺的判定 JSON 行（缺 index）: {}", line);
                return null;
            }
            String conclusion = Verdict.normalize(n.path("conclusion").asText(""));
            if (conclusion.isEmpty() || (!Verdict.HELD.equals(conclusion)
                    && !Verdict.REJECTED.equals(conclusion)
                    && !Verdict.UNCERTAIN.equals(conclusion))) {
                log.warn("丢弃结论非法的判定 JSON 行（{}）: {}", conclusion, line);
                return null;
            }
            return new Verdict(idx.asInt(), conclusion, n.path("reason").asText("").trim());
        } catch (Exception e) {
            log.warn("丢弃非法 JSON 判定行（{}）: {}", e.getMessage(), line);
            return null;
        }
    }

    /** 剥掉旧文本假设行首的 "N. " 序号前缀（有则剥、无则原样），对齐前端 claim 展示 */
    private static String stripLegacyIndex(String cause) {
        Matcher m = LEGACY_HYPO_INDEX.matcher(cause);
        return m.find() ? cause.substring(m.end()).trim() : cause;
    }
}
