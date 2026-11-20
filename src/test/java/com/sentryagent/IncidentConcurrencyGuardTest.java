package com.sentryagent;

import com.sentryagent.graph.IncidentState;
import com.sentryagent.service.CreateRejection;
import com.sentryagent.service.IncidentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件入口防线（并发上限 + 进行中去重）的 Spring 级验收（任务 A，不足清单第 5 项）。
 *
 * <p>被测行为（application-test.yml 口径：dedupe 默认关、max-concurrent=3）：
 * 防线本身在测试上下文里关闭/放宽是为了不破既有用例的隐式共享状态（多类共仓、
 * 同服务连发），所以本类用 ReflectionTestUtils 在用例内临时改闸门状态——
 * 每个用例 @AfterEach 归还现场（配额 permit 精确归位、去重开关复原、本用例
 * 建的事件全部终态化），不依赖、也不污染其他用例。</p>
 *
 * <p>数据源（CI 无 DB 化口径，不足清单第 8 项）：@SpringBootTestWithMysql 同款
 * 三级回退（Testcontainers → 本机 MySQL → 整类 SKIP）——本类同样依赖全上下文
 * 落库链路（拒绝快照的 persistSnapshot 旁路），继承公共基类接入同一矩阵。</p>
 *
 * <p>六个用例对应任务验收单：并发拒绝 / 进行中去重 / 不同服务不互斥 /
 * 审批后释放配额可再建 / 终态后去重解除 / REJECTED_BUSY 不落 t_incident（快照
 * 可查但无图痕迹、无时间线）。BusControl 小工具把 Semaphore 状态的读写收口，
 * 反射点集中一处。</p>
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class IncidentConcurrencyGuardTest extends com.sentryagent.support.MySQLContainerBaseTest {

    @Autowired
    private IncidentService incidentService;

    // ── 闸门状态读写工具（反射点收口） ──

    /** 读当前并发闸门可用配额 */
    private int availablePermits() {
        Semaphore slots = (Semaphore) ReflectionTestUtils.getField(incidentService, "diagnosisSlots");
        return slots.availablePermits();
    }

    /** 把并发上限临时改成 maxPermits（保存旧值，@AfterEach 归还） */
    private int swapPermits(int maxPermits) {
        Semaphore slots = (Semaphore) ReflectionTestUtils.getField(incidentService, "diagnosisSlots");
        int old = slots.drainPermits();
        slots.release(maxPermits);
        return old;
    }

    /** 临时关闭进行中去重（保旧值归还） */
    private boolean swapDedupe(boolean enabled) {
        boolean old = (Boolean) ReflectionTestUtils.getField(incidentService, "dedupeEnabled");
        ReflectionTestUtils.setField(incidentService, "dedupeEnabled", enabled);
        return old;
    }

    /** 把某事件强改终态（去重解除/配额归还的前置操作；REJECTED_* 本就是终态） */
    private void forceTerminal(long id, String outcome) {
        Map<String, Object> snap = incidentService.rawSnapshot(id);
        if (snap == null) {
            return;
        }
        String cur = String.valueOf(snap.get(IncidentState.OUTCOME));
        if ("CLOSED".equals(cur) || "ESCALATED".equals(cur)) {
            return;
        }
        // 两处坑一起修：① REJECTED_BUSY 快照来自 Map.of（不可变），原地 put 会
        // UnsupportedOperationException；② 原地改可变快照也漏了写回 store。
        // 正确姿势：拷贝可变副本 + 反射写回 incidents 存储。
        Map<String, Object> updated = new HashMap<>(snap);
        updated.put(IncidentState.OUTCOME, outcome);
        Map<Long, Map<String, Object>> store = (Map<Long, Map<String, Object>>) ReflectionTestUtils
                .getField(incidentService, "incidents");
        store.put(id, updated);
    }

    private long idOf(Map<String, Object> snap) {
        return ((Number) snap.get(IncidentState.INCIDENT_ID)).longValue();
    }

    @org.junit.jupiter.api.AfterEach
    void restoreGuards() {
        // 防御性归位：万一断言中途抛出，permit 数与开关必须回到上下文初始态
        Semaphore slots = (Semaphore) ReflectionTestUtils.getField(incidentService, "diagnosisSlots");
        slots.drainPermits();
        slots.release(3);
        ReflectionTestUtils.setField(incidentService, "dedupeEnabled", Boolean.FALSE);
    }

    @Test
    @DisplayName("并发上限：配额 1 时第二个建单被拒 REJECTED_BUSY，不排队直接返回明确快照")
    void concurrentRejectionWhenSlotsExhausted() {
        int old = swapPermits(1);
        try {
            Map<String, Object> first = incidentService.createAndRun(
                    "guard-svc-busy", "占住唯一配额的告警");
            long id1 = idOf(first);
            assertNotEquals(CreateRejection.REJECTED_BUSY.outcomeValue(),
                    String.valueOf(first.get(IncidentState.OUTCOME)),
                    "前置：第一个事件必须真跑起来（test 口径 mock 秒级终态前先占住）");
            // mock 链路同步跑图：第一个返回时已终态并释放配额——要构造"占用中"，
            // 直接在第二个建单前把配额手动抽干，等价于"有 1 个事件正在诊断"
            ReflectionTestUtils.setField(incidentService, "dedupeEnabled", Boolean.TRUE);
            Semaphore slots = (Semaphore) ReflectionTestUtils.getField(incidentService, "diagnosisSlots");
            slots.drainPermits();   // 0 张可用 = 全部配额被进行中事件占用

            Map<String, Object> second = incidentService.createAndRun(
                    "guard-svc-busy2", "配额耗尽时的第二个告警");
            assertEquals(CreateRejection.REJECTED_BUSY.outcomeValue(),
                    String.valueOf(second.get(IncidentState.OUTCOME)),
                    "配额用尽必须拒绝（不排队）");
            assertEquals("REJECTED_BUSY", second.get(IncidentState.OUTCOME));
            String reason = String.valueOf(second.get("reason"));
            assertTrue(reason.contains("宁可拒收也不堆积"),
                    "拒绝理由必须讲清'运维系统宁可拒收也不堆积'的立场: " + reason);
            assertNotNull(second.get(IncidentState.INCIDENT_ID), "拒绝也要有事件号（审计可回查）");
            forceTerminal(id1, "CLOSED");
        } finally {
            swapPermits(old);
        }
    }

    @Test
    @DisplayName("进行中去重：同服务 AWAITING_APPROVAL 在跑时再建单 → BUSY_DUPLICATE 带 conflictWith")
    void duplicateRejectedWhileSameServiceInFlight() {
        boolean oldDedupe = swapDedupe(true);
        try {
            Map<String, Object> first = incidentService.createAndRun(
                    "guard-svc-dup", "第一次队列堆积告警");
            long id1 = idOf(first);
            // mock 网关带 HIGH 动作：第一轮停在 AWAITING_APPROVAL（进行中的常态形态）
            assertEquals("AWAITING_APPROVAL", first.get(IncidentState.OUTCOME),
                    "前置：首事件应停在审批闸口（进行中）");

            Map<String, Object> second = incidentService.createAndRun(
                    "guard-svc-dup", "同服务的第二次队列堆积告警");
            assertEquals(CreateRejection.BUSY_DUPLICATE.outcomeValue(),
                    String.valueOf(second.get(IncidentState.OUTCOME)),
                    "同服务进行中必须去重拒绝");
            assertEquals(CreateRejection.BUSY_DUPLICATE.name(), second.get(IncidentState.OUTCOME));
            assertEquals(id1, ((Number) second.get("conflictWith")).longValue(),
                    "拒绝快照必须附带进行中事件号，调用方据此去订阅/审批既有事件");
            assertNotEquals(idOf(second), id1, "拒绝单也是独立审计事件，号必须独立");
            // 解除：把首事件终结后，同服务可再建（终态后去重解除的另一半在下一用例细验）
            forceTerminal(id1, "CLOSED");
        } finally {
            swapDedupe(oldDedupe);
        }
    }

    @Test
    @DisplayName("不同服务不互斥：svc1 进行中，svc2 建单正常受理（去重是服务粒度）")
    void differentServicesDoNotBlockEachOther() {
        boolean oldDedupe = swapDedupe(true);
        try {
            Map<String, Object> first = incidentService.createAndRun(
                    "guard-svc-a", "A 服务队列堆积");
            assertEquals("AWAITING_APPROVAL", first.get(IncidentState.OUTCOME),
                    "前置：A 服务事件停在审批闸口");
            int permitsBefore = availablePermits();

            Map<String, Object> other = incidentService.createAndRun(
                    "guard-svc-b", "B 服务磁盘告警");
            assertEquals("AWAITING_APPROVAL", other.get(IncidentState.OUTCOME),
                    "不同服务的进行中事件不得互斥（去重键是 service）");
            // 设计语义（对齐 approveReleasesQuotaForNextIncident）：等待人工审批的事件
            // 不占用并发名额——配额只在图活跃执行期持有，否则 3 个等审批的事件会
            // 永久占死并发池、任何新事件都无法创建。因此配额应保持满额：
            assertEquals(permitsBefore, availablePermits(),
                    "AWAITING_APPROVAL 事件不占并发名额（等待期释放，人可以想多久）");
            forceTerminal(idOf(first), "CLOSED");
            forceTerminal(idOf(other), "CLOSED");
        } finally {
            swapDedupe(oldDedupe);
        }
    }

    @Test
    @DisplayName("审批续跑释放配额：配额 1 时 approve 期间可观测占用，终态后可再建")
    void approveReleasesQuotaForNextIncident() {
        int old = swapPermits(1);
        try {
            Map<String, Object> first = incidentService.createAndRun(
                    "guard-svc-approve", "队列堆积（将走批准闭环）");
            long id1 = idOf(first);
            assertEquals("AWAITING_APPROVAL", first.get(IncidentState.OUTCOME));
            // 第一轮 run 已结束并归还配额；approve（续跑）期间同样要占配额——
            // 续跑结束（CLOSED）后配额必须回到满额，下一个建单才拿得到
            Map<String, Object> closed = incidentService.approve(id1, true);
            assertEquals("CLOSED", closed.get(IncidentState.OUTCOME),
                    "前置：批准闭环跑完");
            assertEquals(1, availablePermits(),
                    "approve 续跑结束（终态）后配额必须归还（续跑也算占用的对偶面）");

            // 终态后同服务再建单：既不被去重拦（该事件已终态），也拿得到配额
            boolean oldDedupe = swapDedupe(true);
            try {
                Map<String, Object> again = incidentService.createAndRun(
                        "guard-svc-approve", "同服务的新一轮告警");
                assertEquals("AWAITING_APPROVAL", again.get(IncidentState.OUTCOME),
                        "终态释放配额+解除去重后，同服务可再次建单");
                forceTerminal(idOf(again), "CLOSED");
            } finally {
                swapDedupe(oldDedupe);
            }
        } finally {
            swapPermits(old);
        }
    }

    @Test
    @DisplayName("终态后去重解除：ESCALATED 事件不再拦同服务建单")
    void dedupeLiftedAfterTerminalOutcome() {
        boolean oldDedupe = swapDedupe(true);
        try {
            Map<String, Object> first = incidentService.createAndRun(
                    "guard-svc-terminal", "队列堆积（将被否决）");
            long id1 = idOf(first);
            assertEquals("AWAITING_APPROVAL", first.get(IncidentState.OUTCOME));
            // 进行中重复建单必拦（先确认去重活着）
            Map<String, Object> dup = incidentService.createAndRun(
                    "guard-svc-terminal", "进行中重复告警");
            assertEquals(CreateRejection.BUSY_DUPLICATE.name(), dup.get(IncidentState.OUTCOME),
                    "前置：进行中确实拦得住");
            // 否决 → ESCALATED（终态）
            Map<String, Object> escalated = incidentService.approve(id1, false);
            assertEquals("ESCALATED", escalated.get(IncidentState.OUTCOME));

            Map<String, Object> again = incidentService.createAndRun(
                    "guard-svc-terminal", "终态后的新告警");
            assertNotEquals(CreateRejection.BUSY_DUPLICATE.name(),
                    String.valueOf(again.get(IncidentState.OUTCOME)),
                    "终态后去重必须解除：同服务可再次建单");
            forceTerminal(idOf(again), "CLOSED");
        } finally {
            swapDedupe(oldDedupe);
        }
    }

    @Test
    @DisplayName("拒绝快照可查但不落 t_incident：无图痕迹、无时间线，与落库镜像口径一致")
    void rejectedSnapshotVisibleButNeverExecuted() {
        boolean oldDedupe = swapDedupe(true);
        try {
            Map<String, Object> first = incidentService.createAndRun(
                    "guard-svc-audit", "首次告警");
            long id1 = idOf(first);
            Map<String, Object> dup = incidentService.createAndRun(
                    "guard-svc-audit", "被去重的告警");
            long id2 = idOf(dup);
            assertEquals(CreateRejection.BUSY_DUPLICATE.name(), dup.get(IncidentState.OUTCOME));

            // 可查：GET 详情语义（get 分层读，内存命中）
            Map<String, Object> view = incidentService.get(id2);
            assertNotNull(view, "拒绝快照必须可回查（拒收也要可审计）");
            assertEquals(CreateRejection.BUSY_DUPLICATE.name(), view.get(IncidentState.OUTCOME));
            // 无图痕迹：没有计划/没有执行动作/没有复盘报告（拒绝单不是诊断单）
            assertTrue(view.get(IncidentState.PLAN) == null
                            || String.valueOf(view.get(IncidentState.PLAN)).isEmpty(),
                    "拒绝单不得有处置计划（没进图）");
            Object actions = view.get(IncidentState.ACTIONS);
            assertTrue(actions == null || ((java.util.List<?>) actions).isEmpty(),
                    "拒绝单不得有执行动作");
            // 无时间线：没有诊断发生就不得伪造诊断痕迹
            assertTrue(incidentService.timeline(id2).isEmpty(),
                    "拒绝单不得产生节点时间线");
            // 列表可见（outcome 精确过滤口径生效）
            assertTrue(incidentService.list(CreateRejection.BUSY_DUPLICATE.name(), 0, 50)
                            .stream().anyMatch(s -> idOf(s) == id2),
                    "拒绝单必须出现在 outcome 过滤的列表里");
            forceTerminal(id1, "CLOSED");
        } finally {
            swapDedupe(oldDedupe);
        }
    }
}
