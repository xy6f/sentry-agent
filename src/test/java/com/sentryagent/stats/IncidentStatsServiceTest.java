package com.sentryagent.stats;

import com.sentryagent.persist.GraphStepEntity;
import com.sentryagent.persist.GraphStepMapper;
import com.sentryagent.persist.IncidentEntity;
import com.sentryagent.persist.IncidentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IncidentStatsService 口径单测（第四批 4.3）：
 * MTTR 只算 CLOSED；MTTD 只算 auto 且 CLOSED；escalated 率/自愈率分母=终态数；
 * weekly 恒 7 点。数字全部手算钉死，防止"统计粉饰"类回归。
 */
class IncidentStatsServiceTest {

    private IncidentMapper incidentMapper;
    private GraphStepMapper graphStepMapper;
    private IncidentStatsService svc;

    @BeforeEach
    void setUp() {
        incidentMapper = mock(IncidentMapper.class);
        graphStepMapper = mock(GraphStepMapper.class);
        svc = new IncidentStatsService(incidentMapper, graphStepMapper);
    }

    private IncidentEntity inc(long id, String outcome, boolean auto,
                               LocalDateTime start, LocalDateTime closed) {
        IncidentEntity e = new IncidentEntity();
        e.setId(id);
        e.setService("judge-service");
        e.setOutcome(outcome);
        e.setAlertSummary(auto ? "watchman 自动告警：连续 3 次异常" : "手工验证");
        e.setStartedAt(start);
        e.setClosedAt(closed);
        return e;
    }

    @Test
    @DisplayName("混合样本：MTTR/MTTD/自愈率/趋势全对")
    void mixedStats() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 20, 10, 0, 0);
        // 事件 1：auto CLOSED，start→closed 3s，首步在 start+2s → MTTR 3s / MTTD 2s
        IncidentEntity a = inc(1, "CLOSED", true, base, base.plusSeconds(3));
        // 事件 2：manual CLOSED，5s
        IncidentEntity b = inc(2, "CLOSED", false, base, base.plusSeconds(5));
        // 事件 3：auto ESCALATED（不计 MTTR/MTTD，计入分母）
        IncidentEntity c = inc(3, "ESCALATED", true, base, null);
        // 事件 4：AWAITING（非终态，只统计积压）
        IncidentEntity d = inc(4, "AWAITING_APPROVAL", false, base, null);
        when(incidentMapper.selectList(any())).thenReturn(List.of(a, b, c, d));

        GraphStepEntity step = new GraphStepEntity();
        step.setIncidentId(1L);
        step.setNodeName("evidence");
        step.setAt(base.plusSeconds(2));
        when(graphStepMapper.selectList(any())).thenReturn(List.of(step));

        Map<String, Object> out = svc.summary();
        // 计数断言统一 Number.longValue 比较：Map.of 装箱 Integer、聚合可能给 Long，
        // 钉数值语义而非装箱类型（第四批自测抓到的类型脆弱断言）
        assertEquals(4L, num(out.get("total")));
        assertEquals(2L, num(out.get("closed")));
        assertEquals(1L, num(out.get("escalated")));
        assertEquals(1L, num(out.get("awaitingApproval")));
        assertEquals(2L, num(out.get("autoCreated")), "auto 统计含 ESCALATED 样本——发现类字段不是仅 CLOSED 口径");
        assertEquals(4.0, out.get("mttrAvgSec"), "MTTR 平均=(3+5)/2");
        assertEquals(5.0, out.get("mttrP95Sec"), "P95 取上界");
        assertEquals(2.0, out.get("mttdAutoAvgSec"), "MTTD 仅 auto CLOSED 样本");
        assertEquals(66.7, out.get("selfHealRate"), "2/3 终态自愈");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> weekly = (List<Map<String, Object>>) out.get("weekly");
        assertEquals(7, weekly.size(), "近 7 日恒 7 点");
    }

    @Test
    @DisplayName("空表：全 0/空趋势 7 点，不抛")
    void emptyTable() {
        when(incidentMapper.selectList(any())).thenReturn(List.of());
        Map<String, Object> out = svc.summary();
        assertEquals(0L, num(out.get("total")));
        assertNull(out.get("selfHealRate"), "无终态时分母为 0 返回 null 而非除零");
        assertEquals(0.0, out.get("mttrAvgSec"));
    }

    @Test
    @DisplayName("auto 事件缺首步记录：MTTD 样本跳过不炸")
    void autoWithoutSteps() {
        LocalDateTime base = LocalDateTime.now();
        IncidentEntity a = inc(9, "CLOSED", true, base, base.plusSeconds(4));
        when(incidentMapper.selectList(any())).thenReturn(List.of(a));
        when(graphStepMapper.selectList(any())).thenReturn(List.of());
        Map<String, Object> out = svc.summary();
        assertEquals(0.0, out.get("mttdAutoAvgSec"), "无步数样本按 0 均");
        assertEquals(4.0, out.get("mttrAvgSec"));
    }

    /** 计数值读取：忽略 Integer/Long 装箱差异（见 mixedStats 注释） */
    private static long num(Object o) {
        return ((Number) o).longValue();
    }
}
