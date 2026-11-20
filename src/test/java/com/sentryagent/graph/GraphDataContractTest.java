package com.sentryagent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentryagent.llm.MockDiagnoserGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据契约对齐单测（测试计划 G）——mock 网关输出、后端解析器、前端 parsers.js
 * 三方共享的"格式"是三处代码的隐性接缝，任何一角改格式都该让这里变红：
 * <ol>
 *   <li>plan 文本四段解析（含非法行丢弃）——PlanParserTest 已覆盖解析器本身，
 *       这里补"网关产出↔解析器"的对接：makePlan 的入参已升级为结构化提取的
 *       "判定成立"根因（confirmedCauses），其文本必须可解析、白名单合法；</li>
 *   <li>hypotheses↔verdict 结构化对齐（不足清单第 4 项）：假设/判定均为逐行
 *       JSON（{"cause","confidence","verifyAction"} / {"index","conclusion","reason"}），
 *       本测试用 Jackson 模拟前端 JSON.parse 的行为逐行核对字段与序号——
 *       判定 index 错位的假设卡在界面上会显示"无判定"；</li>
 *   <li>非法 JSON 行丢弃 + 旧文本格式兜底（渐进迁移不破坏旧剧本）：
 *       网关/LLM 输出夹杂脏行时，好行保留、坏行丢弃、旧格式照常解析——
 *       解析次序（JSON 优先、旧文本兜底）与前端 parsers.js 逐条一致；</li>
 *   <li>定向补充取证契约（不足清单第 4 项）：verify 节点按假设 verifyAction
 *       走 gatherVerifyEvidence 逐假设取证，回包必须带 [补充证据-定向] 前缀、
 *       含判定数据锚点 cpu=95%、且逐假设互不相同（appender 通道去重会塌缩
 *       全同文本）；旧版 "补充取证:" 前缀走 gatherEvidence 的兼容分支保留；</li>
 *   <li>取证分流按轮次：首轮请求（哪怕告警正文含 cpu/容器/指标 字样）必须拿
 *       [首轮证据]——前端 inferMode 与回环脚本都钉在前缀上，契约一断，集成测试立刻红；</li>
 *   <li>token 记账契约：mock 每次调用固定记 100（TOKENS_PER_CALL），
 *       图级断言（TokenUsageAccountingTest 的 600/700）依赖这个单值锚点，
 *       值一漂那里先红。</li>
 * </ol>
 */
class GraphDataContractTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** mock 网关配独立台账：本测试只验证文本格式契约，token 记账见用例 6 */
    private final MockDiagnoserGateway gw =
            new MockDiagnoserGateway(new com.sentryagent.llm.TokenLedger());

    @Test
    @DisplayName("makePlan 输出满足 PlanParser 四段契约；无成立根因（空串）降级为 NONE 计划")
    void planTextParsesCleanly() {
        String hypotheses = gw.hypothesize("any", "s");
        // 结构化判定：假设1 成立、假设2 不成立 → confirmedCauses 只提取假设1 的 cause
        String verdict = "{\"index\":1,\"conclusion\":\"成立\",\"reason\":\"cpu=95%\"}\n"
                + "{\"index\":2,\"conclusion\":\"不成立\",\"reason\":\"无迹象\"}";
        String cause = HypothesisParser.confirmedCauses(hypotheses, verdict);
        assertFalse(cause.isBlank(), "有成立判定必须提取出根因文本");
        assertFalse(cause.contains("conclusion"), "根因提取不得把判定 JSON 的字段名漏进计划 prompt");
        assertFalse(cause.contains("{\""), "根因提取不得把判定 JSON 原文漏进计划 prompt");

        List<PlanParser.PlanAction> actions = PlanParser.parse(gw.makePlan(cause, "s-1"));
        assertEquals(1, actions.size(), "剧本计划必须恰好解析出 1 条动作");
        PlanParser.PlanAction a = actions.get(0);
        assertEquals("RESTART", a.type());
        assertEquals("HIGH", a.risk());
        assertFalse(a.target().isEmpty());
        assertTrue(a.requiresApproval(), "RESTART 必须走审批令牌护栏");
        assertFalse(PlanParser.isNonePlan(gw.makePlan(cause, "s-1")));

        // 无成立判定/空根因 -> NONE 计划 -> approvalRouter 直跳 retrospect，不挂审批闸口
        String allRejected = "{\"index\":1,\"conclusion\":\"不成立\",\"reason\":\"证据不足\"}";
        assertTrue(PlanParser.isNonePlan(
                gw.makePlan(HypothesisParser.confirmedCauses(hypotheses, allRejected), "s-2")));
        assertTrue(PlanParser.isNonePlan(gw.makePlan("", "s-3")));
    }

    @Test
    @DisplayName("hypothesize/verifyHypotheses 产出逐行 JSON，假设与判定序号逐一对齐（前端 JSON.parse 镜像）")
    void hypothesisVerdictJsonAligned() {
        String evidence = gw.gatherEvidence("judge-service", "alert", 1L, "s");
        String hypotheses = gw.hypothesize(evidence, "s");
        List<String> hLines = lines(hypotheses);
        assertEquals(2, hLines.size(), "剧本必须恰好两条假设");

        // 前端镜像：每行 JSON.parse 后核对字段契约（cause 非空 / confidence 0-100 /
        // verifyAction 显式给出——兜底动作出现说明 mock 的结构化契约漂了）
        List<Hypothesis> hyps = HypothesisParser.parse(hypotheses);
        assertEquals(hLines.size(), hyps.size(), "每行 JSON 都必须可解析为结构化假设");
        for (int i = 0; i < hyps.size(); i++) {
            Hypothesis h = hyps.get(i);
            JsonNode node = assertJsonLine(hLines.get(i));
            assertEquals(h.cause(), node.get("cause").asText(), "cause 字段与解析结果必须一致");
            assertTrue(node.has("confidence"), "结构化假设必须带 confidence 字段（前端置信度徽章）");
            assertTrue(node.has("verifyAction"), "结构化假设必须带 verifyAction 字段（定向取证参数）");
            assertTrue(h.confidence() >= 0 && h.confidence() <= 100, "confidence 必须在 0-100 区间");
            assertEquals(Hypothesis.Status.PENDING, h.status(), "解析态恒 PENDING");
            assertNotEquals(HypothesisParser.DEFAULT_VERIFY_METHOD, h.verifyAction(),
                    "剧本每条假设应显式给验证动作，兜底动作出现说明格式漂了");
        }

        // 用后端解析器构造"定向补充证据"（与 verify 节点同一条路径），保证剧本自洽
        String more = String.join("\n",
                hyps.stream().map(h -> gw.gatherVerifyEvidence(h, "s")).toList());
        String verdict = gw.verifyHypotheses(hypotheses, more, "s");
        List<String> vLines = lines(verdict);
        assertEquals(hyps.size(), vLines.size(), "判定行数与假设行数必须逐一对应");

        // 前端按判定 index 挂对应假设卡：两侧序号集合必须一致（错位=界面"无判定"）
        List<Verdict> verdicts = HypothesisParser.parseVerdicts(verdict);
        assertEquals(hyps.size(), verdicts.size());
        for (int i = 0; i < verdicts.size(); i++) {
            Verdict v = verdicts.get(i);
            JsonNode node = assertJsonLine(vLines.get(i));
            assertEquals(i + 1, v.index(), "判定序号即假设行序");
            assertEquals(v.index(), node.get("index").asInt());
            assertTrue(node.has("conclusion") && node.has("reason"),
                    "结构化判定必须带 conclusion/reason 字段");
            assertTrue(List.of("成立", "不成立", "存疑").contains(v.conclusion()),
                    "结论必须落在白名单内: " + v.conclusion());
        }
        assertTrue(verdicts.get(0).held(), "剧本第一假设（worker 池饱和）应判定成立");
        assertFalse(verdicts.get(1).held(), "剧本第二假设应判定不成立");
        assertTrue(HypothesisParser.hasEstablished(verdict), "剧本判定应驱动放行去 plan");
    }

    @Test
    @DisplayName("非法 JSON 行丢弃 + 旧文本格式兜底：渐进迁移不破坏旧剧本")
    void malformedJsonDroppedAndLegacyTextStillParses() {
        // ── 假设侧：1 合法 JSON + 1 非法 JSON（丢弃）+ 1 缺 cause 残缺 JSON（丢弃）
        //           + 1 缺 verifyAction 的 JSON（验证动作兜底）+ 1 旧文本行（兜底解析）
        String mixed = "{\"cause\":\"池饱和\",\"confidence\":85,\"verifyAction\":\"getMetrics\"}\n"
                + "{\"cause\":\"截断的JSON\", \"confidence\"\n"
                + "{\"confidence\":60,\"verifyAction\":\"getMetrics\"}\n"
                + "{\"cause\":\"缺验证动作的假设\",\"confidence\":40}\n"
                + "3. 旧文本假设 | 查日志尾部\n";
        List<Hypothesis> hyps = HypothesisParser.parse(mixed);
        assertEquals(3, hyps.size(), "合法 JSON + 残缺 JSON 兜底 + 旧文本行各保留一条，脏行丢弃");
        assertEquals("池饱和", hyps.get(0).cause());
        assertEquals(85, hyps.get(0).confidence());
        assertEquals("缺验证动作的假设", hyps.get(1).cause());
        assertEquals(HypothesisParser.DEFAULT_VERIFY_METHOD, hyps.get(1).verifyAction(),
                "JSON 缺 verifyAction 时补兜底动作，定向取证永远有真实参数");
        assertEquals("旧文本假设", hyps.get(2).cause(), "旧文本行兜底解析（行首序号剥掉）");
        assertEquals("查日志尾部", hyps.get(2).verifyAction());
        assertEquals(Hypothesis.DEFAULT_CONFIDENCE, hyps.get(2).confidence(),
                "旧文本不含置信度，取中性默认值");

        // ── 判定侧：1 合法 JSON + 1 缺 index 残缺 JSON（丢弃）+ 1 结论白名单外（丢弃）
        //           + 1 英文状态词（归一回中文判词）+ 1 旧文本行（兜底解析，含存疑）
        String vMixed = "{\"index\":1,\"conclusion\":\"成立\",\"reason\":\"r\"}\n"
                + "{\"conclusion\":\"成立\",\"reason\":\"缺index\"}\n"
                + "{\"index\":2,\"conclusion\":\"胡说\",\"reason\":\"白名单外\"}\n"
                + "{\"index\":3,\"conclusion\":\"CONFIRMED\",\"reason\":\"英文状态词\"}\n"
                + "2 | 存疑 | 待观察\n";
        List<Verdict> vs = HypothesisParser.parseVerdicts(vMixed);
        assertEquals(3, vs.size());
        assertEquals(1, vs.get(0).index());
        assertTrue(vs.get(0).held());
        assertEquals("成立", vs.get(1).conclusion(), "英文 CONFIRMED 归一为中文判词");
        assertEquals("存疑", vs.get(2).conclusion(), "旧文本'存疑'判定兜底可解析");

        // ── 纯旧文本（mock 升级前的产出格式）全链路照常解析：兼容回归钉
        List<Hypothesis> legacyHyps = HypothesisParser.parse(
                "1. 评测 worker 池饱和 | 核对 cpu 指标\n2. 恶意用例死循环");
        assertEquals(2, legacyHyps.size());
        assertEquals("评测 worker 池饱和", legacyHyps.get(0).cause());
        List<Verdict> legacyVerdicts = HypothesisParser.parseVerdicts(
                "1 | 成立 | cpu=95%\n2 | 不成立 | 证据不足");
        assertEquals(2, legacyVerdicts.size());
        assertTrue(legacyVerdicts.get(0).held());
        assertFalse(legacyVerdicts.get(1).held());
        assertTrue(HypothesisParser.hasEstablished("1 | 成立 | cpu=95%"),
                "旧文本判定照样驱动放行——渐进迁移不破坏旧剧本");

        // ── 空防护：null/空白输入产出空列表而非异常
        assertTrue(HypothesisParser.parse(null).isEmpty());
        assertTrue(HypothesisParser.parseVerdicts("  \n ").isEmpty());
    }

    @Test
    @DisplayName("JSON 子串陷阱回归：全'不成立'的 JSON 判定不得被子串推断误读成成立")
    void jsonRejectedVerdictNeverReadAsPositive() {
        // 判定文本升级为 JSON 后，旧实现 replace("不成立","").contains("成立")
        // 会把 {"conclusion":"不成立"} 替换成 {"conclusion":"成立"} —— 全不成立
        // 被误放行去 plan（比文本时代的同款坑更隐蔽）。结构化解析是唯一正解。
        String allRejected = "{\"index\":1,\"conclusion\":\"不成立\",\"reason\":\"证据不足\"}\n"
                + "{\"index\":2,\"conclusion\":\"不成立\",\"reason\":\"证据不足\"}";
        assertFalse(HypothesisParser.hasEstablished(allRejected), "全不成立必须判否");
        assertTrue(HypothesisParser.hasEstablished(
                "{\"index\":1,\"conclusion\":\"成立\",\"reason\":\"r\"}"));
        assertTrue(HypothesisParser.hasEstablished(
                "{\"index\":1,\"conclusion\":\"不成立\",\"reason\":\"r\"}\n"
                        + "{\"index\":2,\"conclusion\":\"成立\",\"reason\":\"r\"}"),
                "混合判定有一条成立即放行");
    }

    @Test
    @DisplayName("gatherVerifyEvidence 定向取证：[补充证据-定向] 前缀、逐假设差异化（appender 去重护栏）")
    void targetedEvidencePerHypothesis() {
        List<Hypothesis> hyps = HypothesisParser.parse(gw.hypothesize("any", "s"));
        assertEquals(2, hyps.size());
        Set<String> texts = new HashSet<>();
        for (Hypothesis h : hyps) {
            String ev = gw.gatherVerifyEvidence(h, "s");
            assertTrue(ev.startsWith("[补充证据-定向]"),
                    "定向取证回包必须带 [补充证据-定向] 前缀（前端徽章锚点）: " + ev);
            assertTrue(ev.contains("cpu=95%"),
                    "定向证据必须含判定数据锚点 cpu=95%，否则回环判定塌成全不成立: " + ev);
            assertTrue(ev.contains("验证项("),
                    "定向证据必须含'验证项'字样（前端 by-verify-method 徽章锚点）: " + ev);
            assertTrue(ev.contains(h.cause()),
                    "回包必须回显验证对象（逐假设差异化的来源）: " + ev);
            texts.add(ev);
        }
        assertEquals(hyps.size(), texts.size(),
                "逐假设证据必须互不相同——appender 通道会去重全同文本，塌缩即证据计数失真");
        // 旧版兼容路径：gatherEvidence 的 "补充取证:" 前缀分流保留（旧剧本不误入首轮分支）
        assertTrue(gw.gatherEvidence("judge-service", "补充取证:核对指标", 1L, "s")
                .startsWith("[补充证据]"));
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

        // 兼容路径的第二轮请求：带旧前缀也必须命中 [补充证据]（新前缀专属定向入口）
        String sup = gw.gatherEvidence("judge-service", "补充取证:核对排队用例耗时分布", 42L, "s");
        assertTrue(sup.startsWith("[补充证据]"),
                "带 '补充取证:' 前缀的请求即补充轮，必须返回 [补充证据]: " + sup);
    }

    @Test
    @DisplayName("token 记账契约：网关每次调用（含定向取证）固定记 100，台账按 sessionId 累加")
    void tokensLedgerAccumulatesPerCall() {
        // mock 单次调用的固定 token 值是单测断言锚点（TokenUsageAccountingTest 依赖 100/次）：
        // 同一会话调 4 次 → 台账读数 400；换会话各自成账，互不串账
        com.sentryagent.llm.TokenLedger ledger = new com.sentryagent.llm.TokenLedger();
        MockDiagnoserGateway g = new MockDiagnoserGateway(ledger);
        Hypothesis h = new Hypothesis("池饱和", 85, "getMetrics", Hypothesis.Status.PENDING);
        g.gatherEvidence("judge-service", "alert", 1L, "s-a");
        g.hypothesize("evidence", "s-a");
        g.gatherVerifyEvidence(h, "s-a");
        g.verifyHypotheses("h", "e", "s-a");
        assertEquals(400L, g.tokensUsed("s-a"), "同会话 4 次调用（含定向取证）= 4 × 100");
        g.gatherEvidence("judge-service", "alert", 2L, "s-b");
        assertEquals(100L, g.tokensUsed("s-b"), "异会话独立分账");
        assertEquals(400L, g.tokensUsed("s-a"), "另一会话记账不得影响前一会话");
        // 无参/零值防御：未知会话与空参数都不产生账目
        assertEquals(0L, g.tokensUsed("unknown-session"));
        ledger.record(null, 100);
        ledger.record("s-a", 0);
        assertEquals(400L, g.tokensUsed("s-a"), "非法记账参数（null 会话/零值）必须被忽略");
    }

    /** 前端镜像第一步：非空行切分（与 parsers.js 的 split('\n')+trim+filter 同款） */
    private static List<String> lines(String text) {
        return text.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 前端镜像第二步：每行必须 JSON.parse 可过（前端 tryJson 对 { 开头行强制解析） */
    private static JsonNode assertJsonLine(String line) {
        try {
            return OM.readTree(line);
        } catch (Exception e) {
            throw new AssertionError("mock 输出行必须是合法 JSON（前端 JSON.parse 契约）: " + line, e);
        }
    }
}
