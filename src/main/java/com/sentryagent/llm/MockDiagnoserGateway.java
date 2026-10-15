package com.sentryagent.llm;

import lombok.extern.slf4j.Slf4j;

/**
 * 测试/演示网关：按计划书第 7 章"队列堆积"剧本返回固定文本，零网络、零模型。
 *
 * <p>剧本设计（与真机 LLM 应输出的格式逐字对齐，这是 mock 的价值所在——
 * mock 输出的"格式契约"变了，图上的解析器就该跟着变，单测会立刻红）：</p>
 * <ol>
 *   <li>取证分流按"轮次"判定，与 ProdDiagnoserGateway 语义对齐（真实链路里
 *       告警驱动的取证就是首轮、verify 节点下发的定向补充取证就是第二轮起）：
 *       凡带 "补充取证:" 前缀的请求（verify 节点拼装）返回 [补充证据]
 *       （cpu=95%、容器超限），其余一律返回 [首轮证据]（timeout killed 日志 + 队列堆积）。
 *       曾用告警正文关键词分流——seed 摘要"多容器 timeout killed"含"容器"字样，
 *       首轮取证被误归成 [补充证据]，前端 inferMode 在 AWAITING 期误判"真实 LLM"，
 *       这是 GUI 测试遗留 2 的修复根因。</li>
 *   <li>假设两行，都带"验证方法"，保证回环的二次取证一定拿到差异化证据。</li>
 *   <li>判定：看到二次证据里的 cpu=95% 即"成立"；否则"不成立"——
 *       "不成立"路径让单测可以手动构造回环。</li>
 *   <li>计划：单条 RESTART HIGH 动作——正好演示"高危必须审批+令牌一次性"护栏。</li>
 *   <li>复盘：固定模板，把过程记录规模写进报告，供断言"报告非空且含事件号"。</li>
 * </ol>
 *
 * <p>装配：sentry.llm.enabled=false（缺省即 mock）时生效，见 {@link LlmConfig}。</p>
 */
@Slf4j
public class MockDiagnoserGateway implements DiagnoserGateway {

    /** 首轮证据：日志关键字与"无日志则无痕迹"的真实世界一致 */
    private static final String FIRST_EVIDENCE =
            "[首轮证据] logs[%s]: 20:1x 起持续出现 \"timeout killed\"; "
                    + "队列深度 320 > 阈值 200 且爬升; deploys[%s]: 最近 24h 无变更";

    /**
     * 二次证据：由"验证方法"驱动的定向取证回包。
     * 注意必须把"本次验证的是哪条方法"（alert 摘要）写进证据文本——
     * LangGraph4j 的 appender 通道默认去重（javap 确认 AppenderChannel 存在
     * ReducerDisallowDuplicate，实测两条全同增量只入一条），若回包不带
     * 差异化字段，verify 节点两次取证在状态里会塌缩成一条、单测证据计数失真。
     */
    private static final String SECOND_EVIDENCE =
            "[补充证据] 验证项(%s) metrics[%s]: cpu=95%%, queueDepth=320; "
                    + "containers: 8 running, 2 restarting（超出 worker 池上限 6）";

    /**
     * 二次取证请求的固定前缀：verify 节点（IncidentGraphFactory）对每条验证
     * 方法下发的定向补充取证都以它开头。轮次判定只认前缀不认正文关键词——
     * 对齐 ProdDiagnoserGateway 的语义：首轮 = 告警直接驱动的取证，
     * 第二轮起 = 验证方法驱动的 "补充取证:" 请求（类注释第 1 条）。
     */
    private static final String SUPPLEMENT_PREFIX = "补充取证:";

    @Override
    public String gatherEvidence(String service, String alert, Long incidentId, String sessionId) {
        String a = alert == null ? "" : alert;
        if (a.startsWith(SUPPLEMENT_PREFIX)) {
            // 补充轮：用 alert（"补充取证:"+验证方法）做差异化标记，多条补充证据彼此不同
            return String.format(SECOND_EVIDENCE, a, service);
        }
        // 首轮：告警正文无论含什么字样（如 seed 摘要里的"多容器 timeout killed"）
        // 都返回带 [首轮证据] 前缀的证据——前端 inferMode 靠该前缀在 AWAITING 期识别 mock
        return String.format(FIRST_EVIDENCE, service, service);
    }

    @Override
    public String hypothesize(String evidence, String sessionId) {
        // 每行 "假设 | 验证方法"——验证方法含 cpu/容器 关键词，触发二次证据分支
        return "1. 评测 worker 池饱和，容器被 docker timeout killed | 核对 cpu 与容器数指标\n"
                + "2. 恶意用例死循环打满队列 | 查看排队用例耗时分布，核对 cpu 指标";
    }

    @Override
    public String verifyHypotheses(String hypotheses, String moreEvidence, String sessionId) {
        String ev = moreEvidence == null ? "" : moreEvidence;
        if (ev.contains("cpu=95%")) {
            return "1 | 成立 | cpu=95% 且运行容器数超池上限，与 worker 池饱和预期一致\n"
                    + "2 | 不成立 | 补充证据未见 TLE 用例风暴迹象";
        }
        // 不含预期数据时判"不成立"——回环路径（verifyRouter -> moreEvidence）由单测专门覆盖
        return "1 | 不成立 | 证据不足\n2 | 不成立 | 证据不足";
    }

    @Override
    public String makePlan(String rootCause, String sessionId) {
        // 修复根因（全量测试 G 抓到）："不成立" 包含子串 "成立"，原判定
        // rootCause.contains("成立") 会把全"不成立"的判定误读成有根因成立、
        // 输出 RESTART 计划。图主链上 verifyRouter 已先剔除"不成立"所以未爆雷，
        // 但网关自身的格式契约必须同口径——先剔除"不成立"再找"成立"。
        if (!rootCause.replace("不成立", "").contains("成立")) {
            return "NONE";
        }
        // 每行 "动作 | 目标 | 风险 | 理由"，格式必须与 PlanParser 契约一致
        return "RESTART | judge-worker-1 | HIGH | 重启被 timeout killed 卡死的评测容器并释放队列";
    }

    @Override
    public String retrospect(Long incidentId, String transcript, String sessionId) {
        int lines = transcript == null ? 0 : transcript.split("\n").length;
        return "【复盘报告 · 事件#" + incidentId + "（mock）】根因：评测 worker 池饱和；"
                + "处置：经审批重启 judge-worker-1；过程记录 " + lines
                + " 行 / " + (transcript == null ? 0 : transcript.length()) + " 字符已归档。";
    }
}
