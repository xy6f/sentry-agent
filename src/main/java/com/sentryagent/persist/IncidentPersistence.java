package com.sentryagent.persist;

import com.sentryagent.graph.IncidentState;
import com.sentryagent.service.NodeProgressListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 事件持久化写侧（Phase 4）：把内存态异步镜像到 MySQL。
 *
 * <p>职责边界（与 IncidentService 的关系）：内存 Map 仍是**事实源**（读路径不变、
 * 图重放语义不变），本类只做**旁路镜像**——写失败只记日志，绝不影响诊断主流程。
 * 这与通知监听器同一条纪律：旁路系统挂了不能拖垮主链路。</p>
 *
 * <p>两个镜像点：
 * 1. 节点完成 → t_graph_step 追加（复用 NodeProgressListener 的 onNodeComplete 调用点，
 *    在 IncidentGraphFactory.timed 装饰器里同时回调两侧）；
 * 2. 事件终态（run 完成 / ESCALATED）→ t_incident insert/update。
 * 镜像点在 IncidentService.notify() 旁统一接线，开关 sentry.persistence.enabled 控制。</p>
 *
 * <p>查询侧（t_incident 历史列表/跨重启读取）留 W 后续：当前 GET /api/incidents/{id}
 * 仍读内存——重启丢内存但 DB 有底账，审计与复盘可查。完整"重启恢复运行中事件"
 * 需要 checkpoint resume（IncidentService 类注释的演进路线），本轮不展开。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sentry.persistence.enabled", havingValue = "true")
public class IncidentPersistence {

    private final IncidentMapper incidentMapper;
    private final GraphStepMapper graphStepMapper;

    public IncidentPersistence(IncidentMapper incidentMapper, GraphStepMapper graphStepMapper) {
        this.incidentMapper = incidentMapper;
        this.graphStepMapper = graphStepMapper;
    }

    /** 图节点完成：追加 t_graph_step（异步，失败只记日志） */
    @Async
    public void saveStep(long incidentId, String node, Map<String, Object> output) {
        try {
            GraphStepEntity e = new GraphStepEntity();
            e.setIncidentId(incidentId);
            e.setNodeName(node);
            e.setDigest(digest(output));
            e.setAt(LocalDateTime.now());
            graphStepMapper.insert(e);
        } catch (Exception e) {
            log.warn("persist graph-step failed (degraded): {} incident={}", e.getMessage(), incidentId);
        }
    }

    /** 事件终态：insert-or-update t_incident（异步） */
    @Async
    public void saveIncident(long incidentId, Map<String, Object> snapshot) {
        try {
            IncidentEntity e = new IncidentEntity();
            e.setId(incidentId);
            e.setService(text(snapshot, IncidentState.SERVICE));
            e.setAlertSummary(truncate(text(snapshot, IncidentState.ALERT), 500));
            e.setOutcome(text(snapshot, IncidentState.OUTCOME));
            e.setRootCause(truncate(text(snapshot, IncidentState.REPORT), 1000));
            Object tokens = snapshot.get(IncidentState.TOTAL_TOKENS);
            e.setTotalTokens(tokens instanceof Integer i ? i : 0);
            e.setStartedAt(LocalDateTime.now());
            e.setClosedAt("CLOSED".equals(e.getOutcome()) || "ESCALATED".equals(e.getOutcome())
                    ? LocalDateTime.now() : null);
            // insert-or-update：同一事件可能多次写（AWAITING → CLOSED）
            if (incidentMapper.selectById(incidentId) == null) {
                incidentMapper.insert(e);
            } else {
                incidentMapper.updateById(e);
            }
        } catch (Exception e) {
            log.warn("persist incident failed (degraded): {} incident={}", e.getMessage(), incidentId);
        }
    }

    /** NodeProgressListener 的落库镜像点（供 IncidentGraphFactory.timed 回调） */
    public void onNodeComplete(long incidentId, String node, Map<String, Object> output) {
        saveStep(incidentId, node, output);
    }

    private static String text(Map<String, Object> snap, String key) {
        return String.valueOf(snap.getOrDefault(key, ""));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String digest(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return "skipped";
        }
        StringBuilder sb = new StringBuilder();
        output.forEach((k, v) -> sb.append(sb.length() > 0 ? "; " : "")
                .append(k).append('=').append(String.valueOf(v)));
        String all = sb.toString();
        return all.length() > 500 ? all.substring(0, 500) + "..." : all;
    }
}
