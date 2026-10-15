package com.sentryagent.graph;

import com.sentryagent.llm.DiagnoserGateway;
import com.sentryagent.service.NodeProgressListener;
import com.sentryagent.tool.EvidenceTools;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 诊断状态图的装配（计划书第 4 章那张 ASCII 图的代码化）。
 *
 * <p>拓扑（与 W1 保持一致）：evidence -> hypothesize -> verify -(条件边)->
 * plan / evidence(回环) / escalate；plan -> awaitApproval -(条件边)->
 * execute / pending-END / retrospect；execute -> retrospect -> END。</p>
 *
 * <p>W2 三处"真实化"，拓扑一根线没动：</p>
 * <ol>
 *   <li>verify 节点由"验证方法"驱动二次定向取证：解析 hypothesize 的
 *       "假设 | 验证方法"行，把每条验证方法作为参数再调一次取证，
 *       新证据走 EVIDENCES appender 通道累积，然后基于新证据判定——
 *       回环回到 evidence 不再是空转，版本链(evidenceSeq/hypoSeq/verdictSeq
 *       + needsRegen 失效标志)保证"重放跳过、回环重算"两个语义同时成立
 *       （详见 IncidentState 类注释）；</li>
 *   <li>plan 文本经 PlanParser 结构化为动作列表，execute 逐动作分派到
 *       @Tool 写操作，每一步都过一次性审批令牌护栏；NONE 计划由
 *       approvalRouter 直跳 retrospect；</li>
 *   <li>STEPS 累加通道 + verifyRouter 最前置的 steps 上限判断：
 *       超过 sentry.max-steps 无条件 escalate（确定性兜底优先于模型行为）。</li>
 * </ol>
 *
 * <p>写法注记：节点先声明为显式泛型的 NodeAction 再交给 node_async 包装——
 * 单参 lambda 带类型声明时编译器无法反推 S extends AgentState，
 * 这是 LangGraph4j 泛型签名的固定写法。</p>
 */
@Slf4j
@Configuration
public class IncidentGraphFactory {

    @Bean
    public CompiledGraph<IncidentState> incidentGraph(
            DiagnoserGateway gateway,
            EvidenceTools tools,
            NodeProgressListener progress,
            @Value("${sentry.max-verify-loops}") int maxLoops,
            @Value("${sentry.max-steps}") int maxSteps) throws GraphStateException {

        // ── 节点：每个节点"只做一件事 + 返回状态增量"。STEPS 恒 +1（全局步数护栏的计数源）；
        //    是否跳过推理由版本链决定，而非简单的"字段非空"──

        NodeAction<IncidentState> evidenceImpl = s -> {
            // 跳过条件两票都否才跳：首轮证据已收(evidences 非空) 且 无人宣告证据失效
            //（审批重放路径）。回环路径 needsRegen=true -> 重新取证、代数 +1、标志复位
            if (!s.evidences().isEmpty() && !s.needsRegen()) {
                return Map.of(IncidentState.STEPS, 1);
            }
            String sessionId = "incident-" + s.incidentId();
            String evidence = gateway.gatherEvidence(
                    s.service(), s.alert(), s.incidentId(), sessionId);
            return Map.of(IncidentState.EVIDENCES, List.of(evidence),
                          IncidentState.EVIDENCE_SEQ, s.evidenceSeq() + 1,
                          IncidentState.NEEDS_REGEN, Boolean.FALSE,
                          IncidentState.STEPS, 1);
        };

        NodeAction<IncidentState> hypothesizeImpl = s -> {
            // 假设的"代"追平了证据的"代" -> 跳过；证据换代（回环重收）-> 重新生成假设
            if (!s.hypotheses().isEmpty() && s.hypoSeq() >= s.evidenceSeq()) {
                return Map.of(IncidentState.STEPS, 1);
            }
            String joined = String.join("\n---\n", s.evidences());
            return Map.of(IncidentState.HYPOTHESES,
                          gateway.hypothesize(joined, "incident-" + s.incidentId()),
                          IncidentState.HYPO_SEQ, s.evidenceSeq(),
                          IncidentState.STEPS, 1);
        };

        // W2 核心升级：验证方法驱动二次取证（计划书 3.1"按假设再取证"的代码化）
        NodeAction<IncidentState> verifyImpl = s -> {
            // 判定追平假设代数 -> 跳过（审批重放路径）；假设换代 -> 重新定向取证并判定
            if (!s.verdict().isEmpty() && s.verdictSeq() >= s.hypoSeq()) {
                return Map.of(IncidentState.STEPS, 1);
            }
            String sessionId = "incident-" + s.incidentId();
            // 抽出每条假设的"验证方法"，作为参数逐条再调一次取证。
            // mock 网关下这就是第二次(及以上) gatherEvidence，返回差异化的补充证据；
            // 生产网关下同一请求交给 LLM 的取证工具自主循环
            List<String> extras = new ArrayList<>();
            for (String method : HypothesisParser.verifyMethods(s.hypotheses())) {
                extras.add(gateway.gatherEvidence(
                        s.service(), "补充取证:" + method, s.incidentId(), sessionId));
            }
            String verdict = gateway.verifyHypotheses(
                    s.hypotheses(), String.join("\n", extras), sessionId);
            boolean established = verdict.replace("不成立", "").contains("成立");
            return Map.of(IncidentState.EVIDENCES, extras,   // appender 通道：新证据累积而非覆盖
                          IncidentState.VERDICT, verdict,
                          IncidentState.VERIFY_ROUND, s.round() + 1,
                          IncidentState.VERDICT_SEQ, s.hypoSeq(),
                          // 不成立：宣告证据集失效，驱动回环重新取证（路由器仍受圈数/步数双上限约束）
                          IncidentState.NEEDS_REGEN, !established,
                          IncidentState.STEPS, 1);
        };

        NodeAction<IncidentState> planImpl = s -> {
            if (!s.plan().isEmpty()) {
                return Map.of(IncidentState.STEPS, 1);
            }
            return Map.of(IncidentState.PLAN,
                          gateway.makePlan(s.verdict(), "incident-" + s.incidentId()),
                          IncidentState.STEPS, 1);
        };

        // 审批闸口：只看"状态里有没有已批准的令牌"。节点自己不签发令牌——
        // 签发是外部审批接口的职责（ApprovalRegistry.issue），节点只消费，防"自己给自己批"
        NodeAction<IncidentState> awaitApprovalImpl = s -> Map.of(IncidentState.STEPS, 1);

        NodeAction<IncidentState> pendingImpl = s ->
                Map.of(IncidentState.OUTCOME, "AWAITING_APPROVAL",
                       IncidentState.STEPS, 1);

        // W2 升级：按计划逐动作执行（W1 只演示单动作）。动作 -> 工具的映射即计划书 4.2 的白名单：
        // RESTART->restartContainer、SCALE->scaleService，其余类型无人可执行、如实记录。
        // 令牌护栏在工具内部强制（一次性 consume），图逻辑不"自觉"——LLM 幻觉出的
        // 高危动作到这里同样撞墙。
        NodeAction<IncidentState> executeImpl = s -> {
            if (!s.actions().isEmpty()) {
                return Map.of(IncidentState.STEPS, 1);
            }
            List<String> results = new ArrayList<>();
            for (PlanParser.PlanAction a : PlanParser.parse(s.plan())) {
                if (a.isNone()) {
                    continue;
                }
                String result = switch (a.type()) {
                    case "RESTART" ->
                            tools.restartContainer(a.target(), s.approval(), s.incidentId());
                    case "SCALE" ->
                            tools.scaleService(a.target(), extractWorkerCount(a.target()),
                                    s.approval(), s.incidentId());
                    default -> "NO_TOOL: 动作 " + a.type() + " 尚无执行工具，转人工（不伪造成功）";
                };
                log.info("execute action {} -> {}", a, result);
                results.add("[" + a.type() + "|" + a.target() + "|" + a.risk() + "] " + result);
            }
            return Map.of(IncidentState.ACTIONS, results, IncidentState.STEPS, 1);
        };

        NodeAction<IncidentState> retrospectImpl = s -> {
            if (!s.report().isEmpty()) {
                return Map.of(IncidentState.OUTCOME, "CLOSED", IncidentState.STEPS, 1);
            }
            String transcript = "告警:" + s.alert() + "\n证据:" + String.join(" | ", s.evidences())
                    + "\n假设:" + s.hypotheses() + "\n判定:" + s.verdict()
                    + "\n计划:" + s.plan() + "\n动作:" + String.join(" | ", s.actions())
                    + "\n步数:" + s.steps();
            String report = gateway.retrospect(s.incidentId(), transcript,
                    "incident-" + s.incidentId());
            return Map.of(IncidentState.REPORT, report,
                          IncidentState.OUTCOME, "CLOSED",
                          IncidentState.STEPS, 1);
        };

        NodeAction<IncidentState> escalateImpl = s ->
                Map.of(IncidentState.OUTCOME, "ESCALATED",
                       IncidentState.REPORT, "自动诊断未收敛（回环耗尽、步数超限或方案不可执行），已转人工。"
                               + "累计步数=" + s.steps() + "，回环=" + s.round(),
                       IncidentState.STEPS, 1);

        // ── 条件边路由：决策逻辑抽到纯类 IncidentRouters（脱离 Spring 逐分支单测）──
        return new StateGraph<>(IncidentState.SCHEMA, IncidentState::new)

                .addNode("evidence",      node_async(timed("evidence", progress, evidenceImpl)))
                .addNode("hypothesize",   node_async(timed("hypothesize", progress, hypothesizeImpl)))
                .addNode("verify",        node_async(timed("verify", progress, verifyImpl)))
                .addNode("plan",          node_async(timed("plan", progress, planImpl)))
                .addNode("awaitApproval", node_async(timed("awaitApproval", progress, awaitApprovalImpl)))
                .addNode("pending",       node_async(timed("pending", progress, pendingImpl)))
                .addNode("execute",       node_async(timed("execute", progress, executeImpl)))
                .addNode("retrospect",    node_async(timed("retrospect", progress, retrospectImpl)))
                .addNode("escalate",      node_async(timed("escalate", progress, escalateImpl)))

                .addEdge(StateGraph.START, "evidence")
                .addEdge("evidence", "hypothesize")
                .addEdge("hypothesize", "verify")
                // 关键回环：verify 不成立且未超限 -> 回到 evidence。二次取证已让新证据进入状态，
                // 这一圈重走 hypothesize 会看到增量证据重生成假设——回环有真实信息量（面试答案）
                .addConditionalEdges("verify",
                        edge_async(s -> IncidentRouters.verifyRouter(s, maxLoops, maxSteps)),
                        Map.of("plan", "plan",
                               "moreEvidence", "evidence",
                               "escalate", "escalate"))
                .addEdge("plan", "awaitApproval")
                .addConditionalEdges("awaitApproval",
                        edge_async(IncidentRouters::approvalRouter),
                        Map.of("pending", "pending",
                               "execute", "execute",
                               "skipExecute", "retrospect"))
                .addEdge("pending", StateGraph.END)
                .addEdge("execute", "retrospect")
                .addEdge("retrospect", StateGraph.END)
                .addEdge("escalate", StateGraph.END)

                // 编译：挂 checkpoint saver（W1 内存实现；W3 换 RedisSaver 即跨进程恢复，
                // 图的全部定义与存储无关——分层收益就在这两行）
                .compile(CompileConfig.builder()
                        .checkpointSaver(new MemorySaver())
                        .build());
    }

    /**
     * 节点计时/进度包装：执行原节点 -> 把产出摘要记入时间线（NodeProgressListener）-> 原样返回。
     * 装饰器模式：不改任何节点逻辑，可观测性挂在装配层，mock/生产两条链路自动同享。
     */
    private static NodeAction<IncidentState> timed(String name,
                                                   NodeProgressListener progress,
                                                   NodeAction<IncidentState> delegate) {
        return state -> {
            Map<String, Object> out = delegate.apply(state);
            progress.onNodeComplete(state.incidentId(), name, out);
            return out;
        };
    }

    /** 从 SCALE 动作的目标文本里取第一个整数作为 worker 数（如 "worker 4->6" -> 6；取不到默认 1） */
    private static int extractWorkerCount(String target) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(
                target == null ? "" : target);
        int last = -1;
        while (m.find()) {
            last = Integer.parseInt(m.group());
        }
        return last < 0 ? 1 : last;
    }
}
