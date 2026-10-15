package com.sentryagent.graph;

import com.sentryagent.llm.MockDiagnoserGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据契约对齐单测（测试计划 G）——mock 网关输出、后端解析器、前端 parsers.js
 * 三方共享的"文本格式"是三处代码的隐性接缝，任何一角改格式都该让这里变红：
 * <ol>
 *   <li>plan 文本四段解析（含非法行丢弃）——PlanParserTest 已覆盖解析器本身，
 *       这里补"网关产出↔解析器"的对接：makePlan 的文本必须可解析、白名单合法；</li>
 *   <li>hypotheses↔verdict 序号对齐：前端 parseHypotheses 用假设行首序号去
 *       verdictByIdx 里查判定，序号错位的假设卡在界面上会显示"无判定"——
 *       本测试把前端正则（/\^(\d+)[.、]?\s*\|\s*(不成立|成立)\s*\|/）镜像进 Java 逐条核对；</li>
 *   <li>取证分流按轮次：verify 节点以 "补充取证:" 前缀下发的请求必须拿
 *       [补充证据]（含 cpu=95%），不带前缀的首轮请求（哪怕告警正文含
 *       cpu/容器/指标 字样）必须拿 [首轮证据]——前端 inferMode 与回环脚本
 *       都钉在这两个前缀上，契约一断，集成测试立刻红。</li>
 * </ol>
 */
class GraphDataContractTest {

    /** 镜像前端 parsers.js parseHypotheses 的假设行正则 */
    private static final Pattern JS_HYPO =
            Pattern.compile("^(\\d+)[.、]?\\s*(.*?)\\s*\\|\\s*(.+)$");
    /** 镜像前端 parsers.js parseHypotheses 的判定行正则（成立/不成立） */
    private static final Pattern JS_VERDICT =
            Pattern.compile("^(\\d+)[.、]?\\s*\\|\\s*(不成立|成立)\\s*\\|\\s*(.*)$");

    private final MockDiagnoserGateway gw = new MockDiagnoserGateway();

    @Test
    @DisplayName("makePlan 输出满足 PlanParser 四段契约；'不成立'输入降级为 NONE 计划")
    void planTextParsesCleanly() {
        String plan = gw.makePlan("1 | 成立 | cpu=95%", "s-1");
        List<PlanParser.PlanAction> actions = PlanParser.parse(plan);
        assertEquals(1, actions.size(), "剧本计划必须恰好解析出 1 条动作");
        PlanParser.PlanAction a = actions.get(0);
        assertEquals("RESTART", a.type());
        assertEquals("HIGH", a.risk());
        assertFalse(a.target().isEmpty());
        assertTrue(a.requiresApproval(), "RESTART 必须走审批令牌护栏");
        assertFalse(PlanParser.isNonePlan(plan));

        // 无成立判定 -> NONE 计划 -> approvalRouter 直跳 retrospect，不挂审批闸口
        assertTrue(PlanParser.isNonePlan(gw.makePlan("1 | 不成立 | 证据不足", "s-2")));
    }

    @Test
    @DisplayName("hypotheses 与 verdict 序号逐一对齐（前端判定卡依赖）")
    void hypothesisVerdictSeqAligned() {
        String evidence = gw.gatherEvidence("judge-service", "alert", 1L, "s");
        String hypotheses = gw.hypothesize(evidence, "s");
        // 用后端解析器构造"补充证据"（与 verify 节点同一条路径），保证剧本自洽
        String more = String.join("\n",
                HypothesisParser.verifyMethods(hypotheses).stream()
                        .map(m -> gw.gatherEvidence("judge-service", "补充取证:" + m, 1L, "s"))
                        .toList());
        String verdict = gw.verifyHypotheses(hypotheses, more, "s");

        // 前端按假设行首序号去 verdictByIdx 查判定：两侧序号集合必须一致
        var hypoIdx = lines(hypotheses).stream()
                .map(l -> match(JS_HYPO, l)).filter(java.util.Objects::nonNull)
                .map(m -> m.group(1)).toList();
        var verdictIdx = lines(verdict).stream()
                .map(l -> match(JS_VERDICT, l)).filter(java.util.Objects::nonNull)
                .map(m -> m.group(1)).toList();
        assertEquals(hypoIdx, verdictIdx, "假设序号与判定序号必须逐一对齐");
        assertFalse(hypoIdx.isEmpty(), "剧本假设不得为空（正则不匹配=契约破坏）");

        // 后端 HypothesisParser 的宽容解析与剧本格式也要咬合：每条假设都有验证方法
        List<HypothesisParser.HypothesisLine> parsed = HypothesisParser.parse(hypotheses);
        assertEquals(hypoIdx.size(), parsed.size());
        assertTrue(parsed.stream().noneMatch(h ->
                        h.verifyMethod().equals(HypothesisParser.DEFAULT_VERIFY_METHOD)),
                "剧本每条假设应显式给验证方法，兜底方法出现说明格式漂了");
    }

    @Test
    @DisplayName("带 '补充取证:' 前缀的请求必须命中二次取证分支（回环脚本的生命线）")
    void verifyMethodsTriggerSecondRoundBranch() {
        String hypotheses = gw.hypothesize("any", "s");
        List<String> methods = HypothesisParser.verifyMethods(hypotheses);
        assertEquals(2, methods.size(), "两条假设应抽出两个去重后的验证方法");
        for (String m : methods) {
            String ev = gw.gatherEvidence("judge-service", "补充取证:" + m, 1L, "s");
            assertTrue(ev.startsWith("[补充证据]") && ev.contains("cpu=95%"),
                    "验证方法 '" + m + "' 未命中二次取证分支，回环会塌缩: " + ev);
        }
        // 而首轮请求（无前缀）必须拿首轮证据——分流契约的另一半
        assertTrue(gw.gatherEvidence("judge-service", "queue up", 1L, "s")
                .startsWith("[首轮证据]"));
    }

    @Test
    @DisplayName("首轮取证按轮次（前缀）而非正文关键词分流：seed 摘要含'容器'也必须拿 [首轮证据]")
    void firstRoundPrefixNotHijackedByAlertKeywords() {
        // GUI 测试遗留 2 的回归钉：一键注入剧本的告警摘要
        // "……多容器 timeout killed" 含关键词"容器"，旧实现按关键词分流，
        // 首轮证据被误标成 [补充证据]，前端 inferMode 在 AWAITING 期误判"真实 LLM"。
        String seedAlert = "队列堆积告警：queueDepth 320 > 阈值 200 且持续爬升，多容器 timeout killed";
        String ev = gw.gatherEvidence("judge-service", seedAlert, 42L, "s");
        assertTrue(ev.startsWith("[首轮证据]"),
                "首轮取证输出必须带 [首轮证据] 前缀（前端 inferMode 的识别锚点）: " + ev);

        // 正文含 cpu/指标 字样的首轮请求同理——前缀才是唯一的轮次信号
        assertTrue(gw.gatherEvidence("judge-service", "cpu 打满，指标异常", 43L, "s")
                .startsWith("[首轮证据]"), "首轮判定不得被告警正文关键词劫持");

        // 第二轮起：verify 节点带前缀的定向取证，哪怕方法正文没有旧关键词
        // （如兜底方法"重新核对日志尾部与关键指标"之外的纯描述），也一律 [补充证据]
        String sup = gw.gatherEvidence("judge-service", "补充取证:核对排队用例耗时分布", 42L, "s");
        assertTrue(sup.startsWith("[补充证据]"),
                "带 '补充取证:' 前缀的请求即补充轮，必须返回 [补充证据]: " + sup);
    }

    private static List<String> lines(String text) {
        return text.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Matcher match(Pattern p, String line) {
        Matcher m = p.matcher(line);
        return m.matches() ? m : null;
    }
}
