package com.sentryagent.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 事件诊断的图状态（计划书 4.1 的 State 落地）。
 *
 * <p>Schema 声明每个 key 的归并语义：证据列表用 appender（节点返回增量、
 * 框架负责累积），标量用默认覆盖。这就是 LangGraph4j 的"类型化状态通道"——
 * 面试问"为什么不把所有东西塞一个 prompt"时，用这个结构回答：
 * 状态是共享黑板，prompt 只领取当前需要的切片。</p>
 *
 * <p>W2 新增两组通道：</p>
 * <ul>
 *   <li>STEPS：累加型通道（reducer 把每次写入当作增量求和，初值 0）。
 *       每个节点执行完写 1，图跑完自然就是"累计步数"——
 *       sentry.max-steps 全局护栏与 verifyRouter 的 escalate 分支直接读它；</li>
 *   <li>证据/假设/判定三条序列号 + 一个失效标志（版本链）。节点"跳过还是重算"
 *       不能只看字段非空：审批后的幂等重放要求全部跳过，而 verify 失败的回环
 *       要求 evidence/hypothesize/verify 全部重算——两者进入节点时的字段状态
 *       完全一样，必须靠额外的代际信息区分。设计：verify 判定不成立时置
 *       needsRegen 失效标志；取证节点见标志就重收并令 evidenceSeq+1；
 *       假设节点比较 hypoSeq 与 evidenceSeq；判定节点比较 verdictSeq 与 hypoSeq。
 *       序列号单向增长、与回环计数 verifyRound 解耦，重放全跳、回环全活，
 *       且不存在"计数器该谁推进"的歧义（踩过的坑：早期用 round 做标记，
 *       回环后 approve 重放时 round 已前进导致全链重算）。</li>
 * </ul>
 */
public class IncidentState extends AgentState {

    public static final String INCIDENT_ID   = "incidentId";
    public static final String SERVICE       = "service";
    public static final String ALERT         = "alert";
    /** 证据清单（累积） */
    public static final String EVIDENCES     = "evidences";
    /** 假设列表文本（覆盖） */
    public static final String HYPOTHESES    = "hypotheses";
    /** 验证判定（覆盖） */
    public static final String VERDICT       = "verdict";
    /** 回环计数（verify 每执行一次 +1），verifyRouter 用它对比 max-verify-loops */
    public static final String VERIFY_ROUND  = "verifyRound";
    /** 处置计划（覆盖） */
    public static final String PLAN          = "plan";
    /** 一次性审批令牌（覆盖，由审批接口注入） */
    public static final String APPROVAL      = "approvalToken";
    /** 执行结果（累积） */
    public static final String ACTIONS       = "actions";
    /** 复盘报告（覆盖） */
    public static final String REPORT        = "report";
    /** 终态：CLOSED/ESCALATED/AWAITING_APPROVAL */
    public static final String OUTCOME       = "outcome";
    /** 累计节点执行步数（累加通道，每节点 +1），sentry.max-steps 护栏读它 */
    public static final String STEPS         = "steps";
    /** 证据集代数：evidence 节点每重新取证 +1 */
    public static final String EVIDENCE_SEQ  = "evidenceSeq";
    /** 假设基于哪一代证据生成 */
    public static final String HYPO_SEQ      = "hypoSeq";
    /** 判定针对哪一代假设做出 */
    public static final String VERDICT_SEQ   = "verdictSeq";
    /** 判定不成立时置位：通知 evidence 节点上一轮证据已失效、回环需重新取证 */
    public static final String NEEDS_REGEN   = "needsRegen";

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            // appender：节点返回增量列表，通道负责累积（证据、动作两条时间线）
            EVIDENCES, Channels.appender(ArrayList<String>::new),
            ACTIONS,   Channels.appender(ArrayList<String>::new),
            // base：默认覆盖语义（后写胜），给 0/false 作初值供应商
            VERIFY_ROUND, Channels.base(() -> 0),
            EVIDENCE_SEQ, Channels.base(() -> 0),
            HYPO_SEQ, Channels.base(() -> 0),
            VERDICT_SEQ, Channels.base(() -> 0),
            NEEDS_REGEN, Channels.base(() -> Boolean.FALSE),
            // 累加 reducer：每次写入视为增量。(old,new) 中 old 为 null 时框架先取默认值 0
            // ——已用 javap 反编译 1.9.3 的 Channel.update 字节码确认该语义
            STEPS, Channels.base((acc, inc) -> ((Integer) acc) + ((Integer) inc), () -> 0));

    public IncidentState(Map<String, Object> initData) {
        super(initData);
    }

    /** 类型化访问器：节点代码只见方法不见字符串 key，读写收口（防拼写漂移） */
    public Long incidentId() { return this.<Long>value(INCIDENT_ID).orElse(-1L); }
    public String service()  { return this.<String>value(SERVICE).orElse(""); }
    public String alert()    { return this.<String>value(ALERT).orElse(""); }
    public List<String> evidences() { return this.<List<String>>value(EVIDENCES).orElse(List.of()); }
    public String hypotheses() { return this.<String>value(HYPOTHESES).orElse(""); }
    public String verdict()    { return this.<String>value(VERDICT).orElse(""); }
    public int round()         { return this.<Integer>value(VERIFY_ROUND).orElse(0); }
    public String plan()       { return this.<String>value(PLAN).orElse(""); }
    public String approval()   { return this.<String>value(APPROVAL).orElse(""); }
    public List<String> actions() { return this.<List<String>>value(ACTIONS).orElse(List.of()); }
    public String report()     { return this.<String>value(REPORT).orElse(""); }
    public String outcome()    { return this.<String>value(OUTCOME).orElse("RUNNING"); }
    public int steps()         { return this.<Integer>value(STEPS).orElse(0); }
    public int evidenceSeq()   { return this.<Integer>value(EVIDENCE_SEQ).orElse(0); }
    public int hypoSeq()       { return this.<Integer>value(HYPO_SEQ).orElse(0); }
    public int verdictSeq()    { return this.<Integer>value(VERDICT_SEQ).orElse(0); }
    public boolean needsRegen(){ return this.<Boolean>value(NEEDS_REGEN).orElse(Boolean.FALSE); }

    /** 判定是否"有假设成立"：先剔除"不成立"再找"成立"，防子串误判（见 IncidentRouters） */
    public boolean verdictEstablished() {
        return verdict().replace("不成立", "").contains("成立");
    }
}
