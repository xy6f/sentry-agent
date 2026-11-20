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
 *   <li>verify 节点由假设驱动的定向补充取证（不足清单第 4 项进一步升级为
 *       结构化回环）：解析 hypothesize 的结构化假设（每行 JSON，旧文本兜底），
 *       按每条假设的 verifyAction 走网关 gatherVerifyEvidence 逐假设"对症下药"
 *       取证，新证据走 EVIDENCES appender 通道累积，然后基于补充证据做
 *       结构化判定（逐行 JSON 判定数组）——回环回到 evidence 不再是空转，
 *       版本链(evidenceSeq/hypoSeq/verdictSeq + needsRegen 失效标志)保证
 *       "重放跳过、回环重算"两个语义同时成立（详见 IncidentState 类注释）；</li>
 *   <li>plan 文本经 PlanParser 结构化为动作列表，execute 逐动作分派到
 *       @Tool 写操作，每一步都过一次性审批令牌护栏；NONE 计划由
 *       approvalRouter 直跳 retrospect。makePlan 的入参是结构化提取的
 *       "判定成立"根因（confirmedCauses），不再是判定原文；</li>
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
            org.springframework.beans.factory.ObjectProvider<com.sentryagent.persist.IncidentPersistence> persistenceProvider,
            @Value("${sentry.max-verify-loops}") int maxLoops,
            @Value("${sentry.max-steps}") int maxSteps,
            @Value("${sentry.token-budget-per-incident}") int tokenBudget) throws GraphStateException {

        // ── 节点：每个节点"只做一件事 + 返回状态增量"。STEPS 恒 +1（全局步数护栏的计数源）；
        //    是否跳过推理由版本链决定，而非简单的"字段非空"──
        // token 增量采用两读数法（README 遗留清单第 2 项的落地）：节点入口读一次网关台账
        // （tokensUsed），推理后再读一次，差值作为 TOTAL_TOKENS 累加通道的增量写入。
        // 这个写法保证三类语义都成立：mock 网关每调用一次记 100（单测断言锚点）、
        // 生产网关由 ChatModelListener 实测记账（tokensUsed 读的是同一份台账）、
        // 审批重放走跳过分支时差值根本不写（totalTokens 不因幂等重放虚增，
        // 与 STEPS 的重放语义对齐）。发起推理的 5 类节点全接账：evidence/hypothesize/
        // verify/plan/retrospect——verify 节点内部含"二次取证 + 判定"两次推理，一次结清。

        NodeAction<IncidentState> evidenceImpl = s -> {
            // 跳过条件两票都否才跳：首轮证据已收(evidences 非空) 且 无人宣告证据失效
            //（审批重放路径）。回环路径 needsRegen=true -> 重新取证、代数 +1、标志复位
            if (!s.evidences().isEmpty() && !s.needsRegen()) {
                return Map.of(IncidentState.STEPS, 1);
            }
            String sessionId = sessionId(s);
            long before = gateway.tokensUsed(sessionId);
            String evidence = gateway.gatherEvidence(
                    s.service(), s.alert(), s.incidentId(), sessionId);
            return Map.of(IncidentState.EVIDENCES, List.of(evidence),
                          IncidentState.EVIDENCE_SEQ, s.evidenceSeq() + 1,
                          IncidentState.NEEDS_REGEN, Boolean.FALSE,
                          IncidentState.STEPS, 1,
                          IncidentState.TOTAL_TOKENS, (int) (gateway.tokensUsed(sessionId) - before));
        };

        NodeAction<IncidentState> hypothesizeImpl = s -> {
            // 假设的"代"追平了证据的"代" -> 跳过；证据换代（回环重收）-> 重新生成假设
            if (!s.hypotheses().isEmpty() && s.hypoSeq() >= s.evidenceSeq()) {
                return Map.of(IncidentState.STEPS, 1);
            }
            String sessionId = "incident-" + s.incidentId();
            long before = gateway.tokensUsed(sessionId);
            String joined = String.join("\n---\n", s.evidences());
            return Map.of(IncidentState.HYPOTHESES,
                          gateway.hypothesize(joined, sessionId),
                          IncidentState.HYPO_SEQ, s.evidenceSeq(),
                          IncidentState.STEPS, 1,
                          IncidentState.TOTAL_TOKENS, (int) (gateway.tokensUsed(sessionId) - before));
        };

        // 不足清单第 4 项核心升级：verify 节点从"形式回环"变"结构化回环"——
        // 解析每条结构化假设（JSON 优先、旧文本兜底），以其 verifyAction 为参数
        // 走网关定向取证入口 gatherVerifyEvidence 逐假设"对症下药"，证据走
        // EVIDENCES appender 累积，再基于补充证据做结构化判定——不是把验证方法
        // 拼进 alert 整段重读（W2 旧做法），而是真的"这个假设说要查什么就去查什么"。
        NodeAction<IncidentState> verifyImpl = s -> {
            // 判定追平假设代数 -> 跳过（审批重放路径）；假设换代 -> 重新定向取证并判定
            if (!s.verdict().isEmpty() && s.verdictSeq() >= s.hypoSeq()) {
                return Map.of(IncidentState.STEPS, 1);
            }
            String sessionId = "incident-" + s.incidentId();
            // 两读数法结账：本节点内含"定向取证×N + 判定"多次推理，入口/出口各读一次
            // 台账，差值一并记入 TOTAL_TOKENS（mock 下即 (N+1)×100）
            long before = gateway.tokensUsed(sessionId);
            // 逐假设定向补充取证：mock 网关按 verifyAction 回包 [补充证据-定向]
            // 针对性文本（逐假设差异化，防 appender 去重塌缩）；生产网关委托
            // Diagnoser 的定向取证 prompt（只查与验证方法直接相关的证据）
            List<String> extras = new ArrayList<>();
            for (Hypothesis h : HypothesisParser.parse(s.hypotheses())) {
                extras.add(gateway.gatherVerifyEvidence(h, sessionId));
            }
            String verdict = gateway.verifyHypotheses(
                    s.hypotheses(), String.join("\n", extras), sessionId);
            // 判定成立与否走结构化解析（JSON 优先、旧文本兜底、白名单全字匹配）——
            // 旧 replace("不成立","").contains("成立") 子串推断在 JSON 判定行上必误判
            boolean established = HypothesisParser.hasEstablished(verdict);
            return Map.of(IncidentState.EVIDENCES, extras,   // appender 通道：新证据累积而非覆盖
                          IncidentState.VERDICT, verdict,
                          IncidentState.VERIFY_ROUND, s.round() + 1,
                          IncidentState.VERDICT_SEQ, s.hypoSeq(),
                          // 不成立：宣告证据集失效，驱动回环重新取证（路由器仍受圈数/步数/预算三上限约束）
                          IncidentState.NEEDS_REGEN, !established,
                          IncidentState.STEPS, 1,
                          IncidentState.TOTAL_TOKENS, (int) (gateway.tokensUsed(sessionId) - before));
        };

        NodeAction<IncidentState> planImpl = s -> {
            if (!s.plan().isEmpty()) {
                return Map.of(IncidentState.STEPS, 1);
            }
            long before = gateway.tokensUsed(sessionId(s));
            // 结构化回环的产出：不再把判定全文塞给 makePlan，而是把"判定成立"
            // 的假设 cause 结构化提取出来（confirmedCauses）作为根因——判定 JSON
            // 的字段名/引号/花括号不再漏进计划 prompt，makePlan 的入参语义从
            // "判定文本"收窄为"确认根因"（无成立根因 = 空串 → 网关产出 NONE 计划）
            return Map.of(IncidentState.PLAN,
                          gateway.makePlan(
                                  HypothesisParser.confirmedCauses(s.hypotheses(), s.verdict()),
                                  sessionId(s)),
                          IncidentState.STEPS, 1,
                          IncidentState.TOTAL_TOKENS, (int) (gateway.tokensUsed(sessionId(s)) - before));
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
                    + "\n步数:" + s.steps() + "\ntoken:" + s.totalTokens();
            long before = gateway.tokensUsed(sessionId(s));
            String report = gateway.retrospect(s.incidentId(), transcript, sessionId(s));
            return Map.of(IncidentState.REPORT, report,
                          IncidentState.OUTCOME, "CLOSED",
                          IncidentState.STEPS, 1,
                          IncidentState.TOTAL_TOKENS, (int) (gateway.tokensUsed(sessionId(s)) - before));
        };

        NodeAction<IncidentState> escalateImpl = s ->
                Map.of(IncidentState.OUTCOME, "ESCALATED",
                       IncidentState.REPORT, "自动诊断未收敛（回环耗尽、步数超限、token 超预算或方案不可执行），已转人工。"
                               + "累计步数=" + s.steps() + "，回环=" + s.round()
                               + "，累计token=" + s.totalTokens(),
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
                        edge_async(s -> IncidentRouters.verifyRouter(s, maxLoops, maxSteps, tokenBudget)),
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

    /** Phase 4 落库镜像 provider：bean 方法参数暂存到字段，timed 装饰器统一接线（见 timed 注释） */
    private final org.springframework.beans.factory.ObjectProvider<com.sentryagent.persist.IncidentPersistence> persistenceProvider;

    public IncidentGraphFactory(
            org.springframework.beans.factory.ObjectProvider<com.sentryagent.persist.IncidentPersistence> persistenceProvider) {
        this.persistenceProvider = persistenceProvider;
    }

    /**
     * 节点计时/进度包装：执行原节点 -> 把产出摘要记入时间线（NodeProgressListener）
     * -> 镜像到 t_graph_step（读路径落库的写侧补齐，不足清单第 1 项）-> 原样返回。
     * 装饰器模式：不改任何节点逻辑，可观测性/落库挂在装配层，mock/生产两条链路自动同享。
     * 读路径落库前镜像点只覆盖 evidence/hypothesize/verify 三节点，重启后 DB 还原的
     * timeline 缺 plan/awaitApproval/pending/execute/retrospect/escalate——
     * 现统一收口到 timed：进度登记与落库镜像对齐到完全相同的节点集合。
     */
    private NodeAction<IncidentState> timed(String name,
                                            NodeProgressListener progress,
                                            NodeAction<IncidentState> delegate) {
        return state -> {
            Map<String, Object> out = delegate.apply(state);
            progress.onNodeComplete(state.incidentId(), name, out);
            mirrorStep(persistenceProvider, state.incidentId(), name, out);
            return out;
        };
    }

    /**
     * Phase 4 落库镜像 helper：persistence 未启用时 provider 无 bean，判空跳过。
     * 独立静态方法（不是塞进 timed）因为 provider 是 Bean 方法参数而非静态可达。
     */
    private static void mirrorStep(
            org.springframework.beans.factory.ObjectProvider<com.sentryagent.persist.IncidentPersistence> persistenceProvider,
            long incidentId, String node, Map<String, Object> out) {
        com.sentryagent.persist.IncidentPersistence p = persistenceProvider.getIfAvailable();
        if (p != null) {
            p.onNodeComplete(incidentId, node, out);
        }
    }

    /** 事件图会话的台账键（= "incident-{id}"，网关 tokensUsed 的分账口径） */
    private static String sessionId(IncidentState s) {
        return "incident-" + s.incidentId();
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
