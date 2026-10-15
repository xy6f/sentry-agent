package com.sentryagent.service;

import com.sentryagent.graph.IncidentState;
import com.sentryagent.tool.ApprovalRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件编排服务：创建事件 -> 驱动图 -> 保存终态快照 -> 支持"审批后续跑"。
 *
 * <p>W1 的续跑策略是"幂等重放"（各节点对已有产出直接跳过，从头再走一遍图，
 * 模型侧几乎零消耗因为跳过的节点不发起推理）；W3 升级为 LangGraph4j 的
 * interrupt + checkpoint resume，真·断点续跑——接口形态不变，只换实现，
 * 注释里把这个演进路线钉死，面试讲演进就用它。</p>
 *
 * <p>可观测性接线（计划书 3.1 Should"SSE 进度"的数据源）：图每个节点完成时
 * IncidentGraphFactory 的 timed 装饰器把 (时间, 节点, outcome 摘要) 记入
 * NodeProgressListener 的事件时间线；本服务的 get() 在返回详情快照时把
 * timeline 字段并入，SSE 端点（IncidentController.stream）补发与轮询新增
 * 读的也是同一个登记处——三条消费路径共享一份事实源，不各记各的账。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentService {

    private final CompiledGraph<IncidentState> graph;
    private final ApprovalRegistry approvalRegistry;
    private final NodeProgressListener progressListener;

    /** 内存事件仓（W2 换 MySQL t_incident，接口不变） */
    private final Map<Long, Map<String, Object>> incidents = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    public Map<String, Object> createAndRun(String service, String alert) {
        long id = idGen.getAndIncrement();
        Map<String, Object> input = new HashMap<>();
        input.put(IncidentState.INCIDENT_ID, id);
        input.put(IncidentState.SERVICE, service);
        input.put(IncidentState.ALERT, alert);
        return run(id, input);
    }

    /** 审批：给事件签发一次性令牌，并驱动图从"审批闸口"继续；终态事件幂等返回现快照 */
    public Map<String, Object> approve(long incidentId, boolean approved) {
        Map<String, Object> snapshot = incidents.get(incidentId);
        if (snapshot == null) {
            throw new IllegalArgumentException("事件不存在: " + incidentId);
        }
        // 终态闸口（GUI 测试遗留观察1 的修复）：对已终结事件（CLOSED/ESCALATED）
        // 重复下决策，原实现会先给 ApprovalRegistry 再签一枚永不消费的令牌——
        // 图重放时 execute 已过、令牌无人 consume，属纯泄漏。
        // 幂等语义：终态不可再翻转，approve/reject 都原样返回现快照，不签发、不重跑。
        String outcome = String.valueOf(snapshot.getOrDefault(IncidentState.OUTCOME, ""));
        if ("CLOSED".equals(outcome) || "ESCALATED".equals(outcome)) {
            log.info("incident {} already terminal ({}), decision approved={} ignored (idempotent)",
                    incidentId, outcome, approved);
            return incidents.get(incidentId);
        }
        Map<String, Object> input = new HashMap<>(snapshot);
        input.put(IncidentState.OUTCOME, "RUNNING");   // 清掉等待标记
        if (approved) {
            input.put(IncidentState.APPROVAL, approvalRegistry.issue(incidentId));
        } else {
            input.put(IncidentState.APPROVAL, "");
            // 拒绝：直接终结（W2 可改为带理由回到 plan 重出方案）。
            // GUI 测试修复（P1）：原实现用 Map.of 整体替换快照，evidences/hypotheses/
            // service/alert/plan 全部丢失——前端 ESCALATED 事件左栏因此空白、
            // 历史行显示 "?"。正确姿势：在既有快照上合并写回，只改 outcome/report 两键。
            Map<String, Object> rejected = new HashMap<>(snapshot);
            rejected.put(IncidentState.OUTCOME, "ESCALATED");
            rejected.put(IncidentState.REPORT, "人工否决自动处置，转人工处置");
            incidents.put(incidentId, rejected);
            return incidents.get(incidentId);
        }
        return run(incidentId, input);
    }

    /**
     * 详情视图：终态快照 + 事件时间线（NodeProgressListener 里该事件的记录）。
     * 合并只发生在这个读侧边界——存回 incidents 的快照保持纯 State 形态，
     * 因为 approve() 会把快照原样重放进图（run 的 input），不能有非 State 杂项。
     */
    public Map<String, Object> get(long id) {
        Map<String, Object> snapshot = incidents.get(id);
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> view = new HashMap<>(snapshot);
        view.put("timeline", progressListener.timeline(id));
        return view;
    }

    /** 原始快照（无 timeline），SSE 端点判断终态用 */
    public Map<String, Object> rawSnapshot(long id) {
        return incidents.get(id);
    }

    /**
     * 事件列表（计划书第 6 章契约 GET /api/incidents?status&page 的服务侧）。
     * 修复根因：全量测试发现该契约端点从未实现，GET /api/incidents 返回 405——
     * 前端历史表靠 localStorage、后端数据面缺位，外部消费方（看板/报表）无入口。
     * 返回纯 State 快照（不带 timeline，列表页不关心节点明细），按事件号升序分页。
     */
    public List<Map<String, Object>> list(String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 20 : size;
        return incidents.values().stream()
                .filter(s -> status == null || status.isBlank()
                        || status.equals(String.valueOf(s.get(IncidentState.OUTCOME))))
                .sorted(Comparator.comparingLong(s ->
                        ((Number) s.getOrDefault(IncidentState.INCIDENT_ID, 0L)).longValue()))
                .skip((long) safePage * safeSize)
                .limit(safeSize)
                .toList();
    }

    /** 时间线单独暴露：SSE 补发/轮询新增的数据源 */
    public java.util.List<NodeProgressListener.TimelineEvent> timeline(long id) {
        return progressListener.timeline(id);
    }

    /** 驱动一次图执行并快照终态 */
    private Map<String, Object> run(long id, Map<String, Object> input) {
        try {
            Optional<IncidentState> finalState = graph.invoke(input);
            Map<String, Object> data = finalState.map(IncidentState::data)
                    .orElse(new HashMap<>(input));
            incidents.put(id, data);
            log.info("incident {} finished outcome={}", id, data.get(IncidentState.OUTCOME));
            return data;
        } catch (Exception e) {
            log.error("incident {} graph execution failed", id, e);
            Map<String, Object> err = new HashMap<>(input);
            err.put(IncidentState.OUTCOME, "FAILED");
            err.put(IncidentState.REPORT, "图执行异常: " + e.getMessage());
            incidents.put(id, err);
            return err;
        }
    }
}
