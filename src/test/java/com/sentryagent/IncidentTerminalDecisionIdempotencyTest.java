package com.sentryagent;

import com.sentryagent.graph.IncidentState;
import com.sentryagent.service.IncidentService;
import com.sentryagent.tool.ApprovalRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 终态事件重复决策的幂等验收（GUI 测试遗留观察①的回归钉）：
 *
 * <p>旧实现缺陷：对已 CLOSED/ESCALATED 的事件再下一枚 decision，
 * approve 分支会先向 ApprovalRegistry 签发一枚新令牌再把快照重放进图——
 * 图各节点走幂等跳过，execute 不会消费这枚令牌（actions 已非空），
 * 于是注册表里留下一枚"永不消费"的泄漏令牌；reject 分支同理还会
 * 反复改写 outcome/report。修复后语义：终态不可再翻转，decision
 * 原样返回现快照——不签发、不重跑、不写回。</p>
 *
 * <p>断言层次（均为行为断言，不依赖修复实现细节）：
 * <ol>
 *   <li>二次 decision 返回的快照 outcome/steps/actions/report 与终结时逐键相等
 *       ——steps 不变即证明图没有被重跑（任何一次重放都会让 STEPS 累加通道涨）；</li>
 *   <li>ApprovalRegistry 内部登记数不变——直接钉死"不新增令牌"。</li>
 * </ol>
 * approve 与 reject 两条分支各测一个终态（CLOSED 再批准、ESCALATED 再否决）。</p>
 */
@SpringBootTest
class IncidentTerminalDecisionIdempotencyTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ApprovalRegistry approvalRegistry;

    /** 注册表当前登记的令牌数：反射读私有 issued 映射，零生产代码侵入 */
    private int registeredTokens() {
        Map<?, ?> issued = (Map<?, ?>) ReflectionTestUtils.getField(approvalRegistry, "issued");
        assertNotNull(issued, "ApprovalRegistry.issued 必须存在");
        return issued.size();
    }

    @Test
    @DisplayName("CLOSED 事件二次 approve：不新增令牌，outcome/actions/steps 逐键不变")
    void approveOnClosedIsIdempotent() {
        // 走完整 happy path：建事件（AWAITING_APPROVAL）→ 批准 → CLOSED
        Map<String, Object> created = incidentService.createAndRun(
                "judge-service", "terminal approve idempotency check");
        long id = ((Number) created.get(IncidentState.INCIDENT_ID)).longValue();
        Map<String, Object> closed = incidentService.approve(id, true);
        assertEquals("CLOSED", closed.get(IncidentState.OUTCOME), "前置：批准应走到 CLOSED");

        // 终结时快照留档 + 令牌计数基线（批准那次签发的令牌已被 execute 消费掉）
        Map<String, Object> before = incidentService.rawSnapshot(id);
        assertNotNull(before);
        int tokensBefore = registeredTokens();

        // ── 二次批准：修复前会再签一枚永不消费的令牌并重跑图（steps 上涨）──
        Map<String, Object> again = incidentService.approve(id, true);

        assertEquals(0, registeredTokens() - tokensBefore,
                "终态事件二次 decision 不得新增审批令牌");
        assertEquals(before.get(IncidentState.OUTCOME), again.get(IncidentState.OUTCOME),
                "终态不可再翻转");
        assertEquals(before.get(IncidentState.STEPS), again.get(IncidentState.STEPS),
                "steps 不变 = 图未被重跑（重放任一节点都会经 STEPS 累加通道 +1）");
        assertEquals(before.get(IncidentState.ACTIONS), again.get(IncidentState.ACTIONS),
                "已执行动作记录不得被二次决策改动");
        assertEquals(before.get(IncidentState.REPORT), again.get(IncidentState.REPORT),
                "复盘报告不得被二次决策改写");
    }

    @Test
    @DisplayName("ESCALATED 事件二次 reject：不新增令牌，outcome/report/steps 逐键不变")
    void rejectOnEscalatedIsIdempotent() {
        Map<String, Object> created = incidentService.createAndRun(
                "judge-service", "terminal reject idempotency check");
        long id = ((Number) created.get(IncidentState.INCIDENT_ID)).longValue();
        Map<String, Object> escalated = incidentService.approve(id, false);
        assertEquals("ESCALATED", escalated.get(IncidentState.OUTCOME), "前置：否决应终结为 ESCALATED");

        Map<String, Object> before = incidentService.rawSnapshot(id);
        assertNotNull(before);
        int tokensBefore = registeredTokens();
        String reportBefore = String.valueOf(before.get(IncidentState.REPORT));

        // ── 二次否决：修复前会再次整体写回快照（report 被反复覆盖）──
        Map<String, Object> again = incidentService.approve(id, false);

        assertEquals(0, registeredTokens() - tokensBefore,
                "reject 分支同理：终态事件二次 decision 不得新增审批令牌");
        assertEquals("ESCALATED", again.get(IncidentState.OUTCOME), "终态不可被反复改写");
        assertEquals(before.get(IncidentState.STEPS), again.get(IncidentState.STEPS),
                "图未被重跑");
        assertEquals(reportBefore, again.get(IncidentState.REPORT),
                "否决报告保持首写内容，不被二次决策覆盖");
        List<?> actions = (List<?>) again.getOrDefault(IncidentState.ACTIONS, List.of());
        assertNotNull(actions);
    }
}
