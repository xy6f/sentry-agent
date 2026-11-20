package com.sentryagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentryagent.graph.Hypothesis;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;

/**
 * 测试/演示网关：按计划书第 7 章"队列堆积"剧本返回固定文本，零网络、零模型。
 *
 * <p>剧本设计（与真机 LLM 应输出的格式逐字对齐，这是 mock 的价值所在——
 * mock 输出的"格式契约"变了，图上的解析器就该跟着变，单测会立刻红）：</p>
 * <ol>
 *   <li>取证分流按"轮次"判定，与 ProdDiagnoserGateway 语义对齐（真实链路里
 *       告警驱动的取证就是首轮）：凡带 "补充取证:" 前缀的请求（旧版 verify 节点
 *       拼装的兼容路径）返回 [补充证据]（cpu=95%、容器超限），其余一律返回
 *       [首轮证据]（timeout killed 日志 + 队列堆积）。曾用告警正文关键词分流——
 *       seed 摘要"多容器 timeout killed"含"容器"字样，首轮取证被误归成
 *       [补充证据]，前端 inferMode 在 AWAITING 期误判"真实 LLM"，
 *       这是 GUI 测试遗留 2 的修复根因。</li>
 *   <li>假设两行 JSON（不足清单第 4 项结构化升级）：字段契约
 *       {"cause","confidence","verifyAction"}，confidence 即 LLM 结构化输出的
 *       置信度位；两条 verifyAction 各有侧重，保证回环的定向取证拿到差异化证据。</li>
 *   <li>定向补充取证（gatherVerifyEvidence）：按假设的 verifyAction 回包带
 *       [补充证据-定向] 前缀的针对性文本，且把验证对象（cause）写进文本——
 *       LangGraph4j 的 appender 通道默认去重（javap 确认 AppenderChannel 存在
 *       ReducerDisallowDuplicate，实测两条全同增量只入一条），回包不带差异化
 *       字段，逐假设取证在状态里会塌缩成一条、单测证据计数失真。</li>
 *   <li>判定：看到定向证据里的 cpu=95% 即第一条"成立"、其余"不成立"；
 *       输出逐行 JSON {"index","conclusion","reason"}（判定数组按行组织，
 *       每行独立可解析——LLM 输出夹杂脏行时逐行丢弃不停摆）——
 *       "不成立"路径让单测可以手动构造回环。</li>
 *   <li>计划：单条 RESTART HIGH 动作——正好演示"高危必须审批+令牌一次性"护栏。
 *       入参 rootCause 是图侧 plan 节点结构化提取的"判定成立"根因
 *       （HypothesisParser.confirmedCauses），空串产出 NONE。</li>
 *   <li>复盘：固定模板，把过程记录规模写进报告，供断言"报告非空且含事件号"。</li>
 * </ol>
 *
 * <p>装配：sentry.llm.enabled=false（缺省即 mock）时生效，见 {@link LlmConfig}。</p>
 */
@Slf4j
public class MockDiagnoserGateway implements DiagnoserGateway {

    /**
     * mock 单次调用的固定 token 消耗（每节点 100）：台账累加的单测锚点——
     * 图跑完 totalTokens 恰等于"发起推理的节点数 × 100"（跳过分支不记账），
     * 断言写死这个数即可发现"该计数的没计 / 不该计的计了"两类回归。
     * verify 节点内含"定向取证×N + 判定"（N=假设条数），同样逐次记账。
     */
    static final long TOKENS_PER_CALL = 100L;

    /** 结构化输出序列化器：LinkedHashMap 保持 key 顺序即契约展示顺序 */
    private static final ObjectMapper OM = new ObjectMapper();

    /** 分账台账：mock 与 prod 共用同一份口径（见 {@link TokenLedger}） */
    private final TokenLedger ledger;

    /** LlmConfig 装配时注入共享台账（与 Prod 网关同源，口径不分家） */
    public MockDiagnoserGateway(TokenLedger ledger) {
        this.ledger = ledger;
    }

    /** 首轮证据：日志关键字与"无日志则无痕迹"的真实世界一致 */
    private static final String FIRST_EVIDENCE =
            "[首轮证据] logs[%s]: 20:1x 起持续出现 \"timeout killed\"; "
                    + "队列深度 320 > 阈值 200 且爬升; deploys[%s]: 最近 24h 无变更";

    /**
     * 定向补充证据：由假设的 verifyAction 驱动的"对症下药"回包
     * （不足清单第 4 项）。前缀 [补充证据-定向] 是前端证据卡的定向徽章锚点；
     * "验证项(" 字样沿用旧版 by-verify-method 徽章的识别锚点；文本里必须含
     * cpu=95%（verifyHypotheses 判定成立的数据锚点）。
     */
    private static final String TARGETED_EVIDENCE =
            "[补充证据-定向] 验证项(%s) metrics[%s]: cpu=95%%, queueDepth=320; "
                    + "containers: 8 running, 2 restarting（超出 worker 池上限 6）";

    /**
     * 兼容路径的二次证据：旧版 verify 节点把验证方法拼进 alert（"补充取证:"
     * 前缀）走 gatherEvidence 的回包格式。verify 节点已改走 gatherVerifyEvidence
     * 定向入口，本分支仅为旧剧本/手工调用保留分流语义。
     */
    private static final String SECOND_EVIDENCE =
            "[补充证据] 验证项(%s) metrics[%s]: cpu=95%%, queueDepth=320; "
                    + "containers: 8 running, 2 restarting（超出 worker 池上限 6）";

    /**
     * 二次取证请求的固定前缀（兼容路径）：对齐 ProdDiagnoserGateway 的语义：
     * 首轮 = 告警直接驱动的取证，第二轮起 = 验证方法驱动的 "补充取证:" 请求。
     */
    private static final String SUPPLEMENT_PREFIX = "补充取证:";

    @Override
    public String gatherEvidence(String service, String alert, Long incidentId, String sessionId) {
        String a = alert == null ? "" : alert;
        if (a.startsWith(SUPPLEMENT_PREFIX)) {
            // 补充轮（兼容路径）：用 alert（"补充取证:"+验证方法）做差异化标记
            ledger.record(sessionId, TOKENS_PER_CALL);
            return String.format(SECOND_EVIDENCE, a, service);
        }
        // 首轮：告警正文无论含什么字样（如 seed 摘要里的"多容器 timeout killed"）
        // 都返回带 [首轮证据] 前缀的证据——前端 inferMode 靠该前缀在 AWAITING 期识别 mock
        ledger.record(sessionId, TOKENS_PER_CALL);
        return String.format(FIRST_EVIDENCE, service, service);
    }

    @Override
    public String gatherVerifyEvidence(Hypothesis hypothesis, String sessionId) {
        ledger.record(sessionId, TOKENS_PER_CALL);
        // 针对性回包：把验证对象（cause）与验证动作都写进证据文本——
        // 逐假设差异化是硬要求（见类注释第 3 条 appender 去重），同时
        // 前端 parseEvidence 靠 [补充证据-定向] 前缀与"验证项("字样打徽章
        return String.format(TARGETED_EVIDENCE,
                hypothesis.cause(), hypothesis.verifyAction());
    }

    @Override
    public String hypothesize(String evidence, String sessionId) {
        ledger.record(sessionId, TOKENS_PER_CALL);
        // 结构化假设（不足清单第 4 项）：每行一个 JSON 对象，字段契约与
        // HypothesisParser.parse / 前端 parsers.js.parseHypotheses 三方共享。
        // 生产侧对应 prompt（Diagnoser.hypothesize 的 @SystemMessage）同步升级为
        // 产 JSON——LLM 结构化输出的前端契约升级位；旧文本格式由解析器兜底兼容。
        return jsonLine("cause", "评测 worker 池饱和，容器被 docker timeout killed",
                "confidence", 85, "verifyAction", "getMetrics 核对 cpu 与容器数指标")
                + "\n" + jsonLine("cause", "恶意用例死循环打满队列",
                "confidence", 55, "verifyAction", "getMetrics 查看排队用例耗时分布，核对 cpu 指标");
    }

    @Override
    public String verifyHypotheses(String hypotheses, String moreEvidence, String sessionId) {
        String ev = moreEvidence == null ? "" : moreEvidence;
        ledger.record(sessionId, TOKENS_PER_CALL);
        boolean established = ev.contains("cpu=95%");
        // 判定条数与假设行数逐一对齐（序号即行号，JSON 与旧文本输入都按行计数）——
        // 前端按判定 index 去挂对应假设卡，序号错位会显示"无判定"
        int n = countLines(hypotheses);
        if (n == 0) {
            return "";   // 无假设可判：输出空（路由按"不成立"路径处理回环/转人工）
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            if (established && i == 1) {
                sb.append(jsonLine("index", i, "conclusion", "成立",
                        "reason", "cpu=95% 且运行容器数超池上限，与 worker 池饱和预期一致"));
            } else if (established) {
                sb.append(jsonLine("index", i, "conclusion", "不成立",
                        "reason", "定向补充证据未见该假设预期的迹象"));
            } else {
                sb.append(jsonLine("index", i, "conclusion", "不成立", "reason", "证据不足"));
            }
        }
        return sb.toString();
    }

    @Override
    public String makePlan(String rootCause, String sessionId) {
        ledger.record(sessionId, TOKENS_PER_CALL);
        // 入参已是图侧结构化提取的"判定成立"根因（confirmedCauses），空 = 无成立根因
        // → NONE 计划 → approvalRouter 直跳 retrospect。历史坑（保留注释）：
        // 旧实现从判定全文用 replace("不成立","").contains("成立") 找子串，
        // "不成立"会误命中——判定升级为 JSON 后这种子串推断彻底作废，
        // 结构化提取让"防伪"在解析层一次做完。
        if (rootCause == null || rootCause.isBlank()) {
            return "NONE";
        }
        // 每行 "动作 | 目标 | 风险 | 理由"，格式必须与 PlanParser 契约一致
        return "RESTART | judge-worker-1 | HIGH | 重启被 timeout killed 卡死的评测容器并释放队列";
    }

    @Override
    public String retrospect(Long incidentId, String transcript, String sessionId) {
        ledger.record(sessionId, TOKENS_PER_CALL);
        int lines = transcript == null ? 0 : transcript.split("\n").length;
        return "【复盘报告 · 事件#" + incidentId + "（mock）】根因：评测 worker 池饱和；"
                + "处置：经审批重启 judge-worker-1；过程记录 " + lines
                + " 行 / " + (transcript == null ? 0 : transcript.length()) + " 字符已归档。";
    }

    /** mock 台账读数：每次调用固定 {@link #TOKENS_PER_CALL}，累计即调用次数 × 100 */
    @Override
    public long tokensUsed(String sessionId) {
        return ledger.used(sessionId);
    }

    /** 以 JSON 序列化拼装一行结构化输出（保证引号/中文转义合法，key 顺序即契约顺序） */
    private static String jsonLine(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        try {
            return OM.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("mock 结构化输出序列化失败", e);
        }
    }

    /** 非空行计数（假设列表的行数即判定条数的对齐基准） */
    private static int countLines(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) java.util.Arrays.stream(text.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .count();
    }
}
