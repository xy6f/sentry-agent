package com.sentryagent.stats;

import com.sentryagent.persist.GraphStepEntity;
import com.sentryagent.persist.IncidentEntity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sentryagent.persist.GraphStepMapper;
import com.sentryagent.persist.IncidentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MTTD/MTTR 统计服务（第四批 4.3：把"定位时长从 X 降到 Y"的叙事变成可查询的数）。
 *
 * <p>口径定义（诚实优先，注释即文档）：
 * MTTD（平均发现时长）：watchman 自动建的事件，started_at（告警进入系统时刻）
 * 到 t_graph_step 首个节点完成（首轮证据到手、故障被"确认知晓"）的间隔；
 * 手动 create 的事件 MTTD≈0（人已经发现了），统计单列 auto/manual 两组避免混算骗人。
 * MTTR（平均恢复时长）：CLOSED 事件的 started_at→closed_at；
 * 等待审批/升级转人工的时长不计入 MTTR（那是"没恢复"，混进去会拉低数字粉饰效果）,
 * 单独看 escalated 率与待审批积压数。</p>
 *
 * <p>数据源是 Phase 4 落库的底账（非内存），所以统计天然跨重启；
 * 依赖 sentry.persistence.enabled=true，与落库同开关装配。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sentry.persistence.enabled", havingValue = "true", matchIfMissing = true)
public class IncidentStatsService {

    private final IncidentMapper incidentMapper;
    private final GraphStepMapper graphStepMapper;

    /** 汇总报告：总量 + MTTR/MTTD（自动/手动分组）+ 成功率 + 近 7 日 MTTR 趋势 */
    public Map<String, Object> summary() {
        List<IncidentEntity> all = incidentMapper.selectList(Wrappers.emptyWrapper());

        List<Long> mttrClosedMs = new ArrayList<>();
        List<Long> mttdAutoMs = new ArrayList<>();
        long closed = 0, escalated = 0, awaiting = 0, running = 0, autoCount = 0;

        for (IncidentEntity e : all) {
            boolean isAuto = e.getAlertSummary() != null
                    && e.getAlertSummary().startsWith("watchman 自动告警");
            if (isAuto) {
                autoCount++;
            }
            switch (String.valueOf(e.getOutcome())) {
                case "CLOSED" -> {
                    closed++;
                    if (e.getClosedAt() != null) {
                        mttrClosedMs.add(Duration.between(e.getStartedAt(), e.getClosedAt()).toMillis());
                    }
                    if (isAuto) {
                        Long mttd = mttdOf(e);
                        if (mttd != null) {
                            mttdAutoMs.add(mttd);
                        }
                    }
                }
                case "ESCALATED" -> escalated++;
                case "AWAITING_APPROVAL" -> awaiting++;
                default -> running++;
            }
        }

        long terminal = closed + escalated;
        Map<String, Object> out = new HashMap<>();
        out.put("total", all.size());
        out.put("closed", closed);
        out.put("escalated", escalated);
        out.put("awaitingApproval", awaiting);
        out.put("running", running);
        out.put("autoCreated", autoCount);
        out.put("mttrAvgSec", round1(avg(mttrClosedMs) / 1000.0));
        out.put("mttrP95Sec", round1(percentile(mttrClosedMs, 0.95) / 1000.0));
        out.put("mttdAutoAvgSec", round1(avg(mttdAutoMs) / 1000.0));
        out.put("selfHealRate", terminal == 0 ? null
                : round1(closed * 100.0 / terminal));
        out.put("weekly", weekly(mttrClosedMs, all));
        return out;
    }

    /** MTTD：started_at → 该事件首个图步骤（证据到手） */
    private Long mttdOf(IncidentEntity e) {
        List<GraphStepEntity> steps = graphStepMapper.selectList(
                Wrappers.<GraphStepEntity>lambdaQuery()
                        .eq(GraphStepEntity::getIncidentId, e.getId())
                        .orderByAsc(GraphStepEntity::getAt)
                        .last("LIMIT 1"));
        if (steps.isEmpty()) {
            return null;
        }
        return Duration.between(e.getStartedAt(), steps.get(0).getAt()).toMillis();
    }

    /** 近 7 日 MTTR 趋势：按 closed_at 所在日聚合当日平均（前端趋势图数据源） */
    private List<Map<String, Object>> weekly(List<Long> mttrClosedMs, List<IncidentEntity> all) {
        Map<LocalDate, List<Long>> byDay = new HashMap<>();
        for (IncidentEntity e : all) {
            if (!"CLOSED".equals(e.getOutcome()) || e.getClosedAt() == null
                    || e.getStartedAt() == null) {
                continue;
            }
            byDay.computeIfAbsent(e.getClosedAt().toLocalDate(), d -> new ArrayList<>())
                    .add(Duration.between(e.getStartedAt(), e.getClosedAt()).toMillis());
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            List<Long> vals = byDay.getOrDefault(day, List.of());
            trend.add(Map.of("date", day.toString(),
                    "count", vals.size(),
                    "avgSec", round1(avg(vals) / 1000.0)));
        }
        return trend;
    }

    private static double avg(List<Long> xs) {
        return xs.isEmpty() ? 0 : xs.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static double percentile(List<Long> xs, double p) {
        if (xs.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(xs);
        sorted.sort(Comparator.naturalOrder());
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
