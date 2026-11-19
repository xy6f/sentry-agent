package com.sentryagent.persist;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sentryagent.graph.IncidentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件查询服务（读路径落库，不足清单第 1 项）：把 t_incident / t_graph_step
 * 的落库数据组装回"内存快照同形"的读视图。
 *
 * <p>职责边界（与 IncidentService 的关系）：IncidentService 的内存 Map 仍是
 * <b>运行中事件的事实源</b>（图重放语义不变）；本类是<b>重启后历史事件的事实源</b>
 * ——进程重启内存清空，但 t_incident/t_graph_step 有完整底账（IncidentPersistence
 * 异步镜像的产物），读路径从 DB 把历史事件还原出来。只读、无副作用：
 * 绝不把 DB 视图回灌内存（否则 approve() 会把只有镜像字段的视图重放进图，
 * 破坏图重放语义——重启续跑属 checkpoint resume 演进路线，本轮不展开）。</p>
 *
 * <p>JSON 形状契约（前端零改动的红线）：字段名与内存快照逐一对应
 * （incidentId/service/alert/outcome/report/totalTokens/steps/timeline...），
 * timeline 条目与 NodeProgressListener.TimelineEvent 的序列化形态对齐——
 * {at(epoch millis), node, digest}，前端 fmtTime 直接 new Date(at) 可解析。
 * 镜像字段之外的诊断过程通道（evidences/hypotheses/verdict/plan/actions）
 * 当前只落 report 一份，其余给类型安全的空值默认——前端各面板对这些字段
 * 均做了 || [] / || '' 兜底，不会空白报错；补齐过程通道属镜像列扩展，后续演进。</p>
 *
 * <p>开关纪律：与 IncidentPersistence 同一开关 sentry.persistence.enabled，
 * 关闭时不装配（纯内存形态连 Mapper 都不碰）；DB 抖动向上抛由调用方
 * （IncidentService 分层读）统一降级——读路径挂了不能拖垮主链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sentry.persistence.enabled", havingValue = "true")
public class IncidentQueryService {

    private final IncidentMapper incidentMapper;
    private final GraphStepMapper graphStepMapper;

    /**
     * 详情视图（对齐 GET /api/incidents/{id} 的内存形态）：事件字段 + timeline。
     * 查无此事件返回 null（调用方据此返回 404，与内存路径语义一致）。
     */
    public Map<String, Object> detail(long id) {
        IncidentEntity e = incidentMapper.selectById(id);
        if (e == null) {
            return null;
        }
        List<Map<String, Object>> timeline = dbTimeline(id);
        Map<String, Object> view = rowView(e, timeline.size());
        view.put("timeline", timeline);
        return view;
    }

    /**
     * 列表行（对齐 GET /api/incidents 的内存形态）：纯事件字段、不带 timeline
     * （列表页不关心节点明细，这是列表契约的既有约定）。
     * status 为 null/blank 时不过滤，否则按 outcome 精确匹配（DB 侧 WHERE，不整表捞）。
     * 不分页——分页与"内存+DB 合并去重排序"统一收口在 IncidentService.list，
     * 本方法只负责把 DB 的历史行捞全。
     */
    public List<Map<String, Object>> listRows(String status) {
        LambdaQueryWrapper<IncidentEntity> qw = new LambdaQueryWrapper<IncidentEntity>()
                .orderByAsc(IncidentEntity::getId);
        if (status != null && !status.isBlank()) {
            qw.eq(IncidentEntity::getOutcome, status);
        }
        List<IncidentEntity> rows = incidentMapper.selectList(qw);
        if (rows.isEmpty()) {
            return List.of();
        }
        // steps 一次分组查询补齐（每行一次 COUNT 会 N+1，演示规模其实无所谓，
        // 但一次 in + 内存分组同样简单，顺手把查询次数钉在常数）
        Map<Long, Integer> counts = stepCounts(rows.stream().map(IncidentEntity::getId).toList());
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (IncidentEntity e : rows) {
            result.add(rowView(e, counts.getOrDefault(e.getId(), 0)));
        }
        return result;
    }

    /** DB 历史最大事件号（IncidentService 启动时据此播种 idGen，防重启后撞号覆盖底账） */
    public long maxIncidentId() {
        IncidentEntity last = incidentMapper.selectOne(new LambdaQueryWrapper<IncidentEntity>()
                .orderByDesc(IncidentEntity::getId)
                .last("LIMIT 1"));
        return last == null || last.getId() == null ? 0L : last.getId();
    }

    /** 一批事件的 t_graph_step 行数分组（steps = 实际执行过的节点数，与内存语义一致） */
    private Map<Long, Integer> stepCounts(List<Long> ids) {
        List<GraphStepEntity> steps = graphStepMapper.selectList(
                new LambdaQueryWrapper<GraphStepEntity>().in(GraphStepEntity::getIncidentId, ids));
        Map<Long, Integer> counts = new HashMap<>();
        for (GraphStepEntity s : steps) {
            counts.merge(s.getIncidentId(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * t_graph_step → timeline：按落库主键升序（即写入顺序）组装，
     * 条目形状 {at, node, digest} 与 NodeProgressListener.TimelineEvent 的
     * JSON 序列化形态逐字段对齐（at 用 epoch millis，前端 new Date(at) 直接可解析）。
     */
    private List<Map<String, Object>> dbTimeline(long id) {
        List<GraphStepEntity> steps = graphStepMapper.selectList(
                new LambdaQueryWrapper<GraphStepEntity>()
                        .eq(GraphStepEntity::getIncidentId, id)
                        .orderByAsc(GraphStepEntity::getId));
        List<Map<String, Object>> timeline = new ArrayList<>(steps.size());
        for (GraphStepEntity s : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("at", toMillis(s.getAt()));
            item.put("node", s.getNodeName());
            item.put("digest", s.getDigest() == null ? "" : s.getDigest());
            timeline.add(item);
        }
        return timeline;
    }

    /** DATETIME → epoch millis（JDBC 侧 serverTimezone 与 JVM 默认时区同源，直接按系统时区读回） */
    private static long toMillis(LocalDateTime at) {
        return at == null ? System.currentTimeMillis()
                : at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * t_incident 行 → 内存快照同形的读视图（key 用 IncidentState 常量防拼写漂移）。
     * steps 由调用方传入（详情=timeline 条数，列表=分组计数），其余镜像字段直读、
     * 缺失的过程通道给类型安全空值（前端 || [] / || '' 兜底，不空白报错）。
     */
    private static Map<String, Object> rowView(IncidentEntity e, int steps) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put(IncidentState.INCIDENT_ID, e.getId());
        v.put(IncidentState.SERVICE, e.getService());
        v.put(IncidentState.ALERT, e.getAlertSummary());
        v.put(IncidentState.EVIDENCES, List.of());
        v.put(IncidentState.HYPOTHESES, "");
        v.put(IncidentState.VERDICT, "");
        v.put(IncidentState.PLAN, "");
        v.put(IncidentState.ACTIONS, List.of());
        v.put(IncidentState.OUTCOME, e.getOutcome());
        v.put(IncidentState.REPORT, e.getRootCause() == null ? "" : e.getRootCause());
        v.put(IncidentState.TOTAL_TOKENS, e.getTotalTokens() == null ? 0 : e.getTotalTokens());
        v.put(IncidentState.STEPS, steps);
        return v;
    }
}
