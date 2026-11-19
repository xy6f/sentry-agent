package com.sentryagent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sentryagent.graph.IncidentState;
import com.sentryagent.persist.GraphStepEntity;
import com.sentryagent.persist.GraphStepMapper;
import com.sentryagent.persist.IncidentEntity;
import com.sentryagent.persist.IncidentMapper;
import com.sentryagent.persist.IncidentQueryService;
import com.sentryagent.service.IncidentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重启恢复读路径（不足清单第 1 项的验收用例）：
 *
 * <p>场景模拟"进程重启"：直接用 Mapper 把一条事件底账写进 t_incident/t_graph_step
 * （等价于上一个进程 IncidentPersistence 镜像的产物），随后把 IncidentService 的
 * 内存仓清空（ReflectionTestUtils 抹掉 ConcurrentHashMap，等价 JVM 重启后内存归零），
 * 再走读路径断言：</p>
 * <ol>
 *   <li>GET 详情语义（IncidentService.get）→ 内存未命中 → IncidentQueryService
 *       从 DB 还原：字段名与内存快照同形（incidentId/service/alert/outcome/
 *       report/totalTokens/steps），timeline 从 t_graph_step 按 incident_id
 *       组装且条目形状 {at(epoch millis), node, digest} 与
 *       NodeProgressListener.TimelineEvent 的序列化形态对齐（前端零改动的红线）；</li>
 *   <li>AWAITING_APPROVAL 状态的事件重启后从 DB 恢复展示（"重启续跑"不在本轮，
 *       断言它查得到但 approve 不读 DB——不重放进图）；</li>
 *   <li>分层读边界：内存里存在的事件仍走内存（时间线来自 NodeProgressListener
 *       登记处，不由 DB 覆盖）。</li>
 * </ol>
 *
 * <p>测试库为本地 MySQL（application-test.yml 同生产配置，schema.sql 幂等建表），
 * 用例内使用远超现有底账的 ID 段（990xxx）避免与其他用例/历史数据撞号，写侧
 * 收尾清理自己插入的行——不依赖、也不污染其他用例的隐式共享状态。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class IncidentRestartRecoveryTest {

    /** 测试专用 ID 段：远超现有底账，防撞号（idGen 播种后新事件号远小于此段） */
    private static final long ID_CLOSED = 990001L;
    private static final long ID_AWAITING = 990002L;
    private static final long ID_MEMORY = 990003L;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private com.sentryagent.service.NodeProgressListener progressListener;

    @Autowired
    private IncidentQueryService queryService;

    @Autowired
    private IncidentMapper incidentMapper;

    @Autowired
    private GraphStepMapper graphStepMapper;

    @Test
    @DisplayName("重启恢复：DB 底账 → 内存清空 → get() 还原详情（字段+timeline 形状）")
    void restartThenDetailReadFromDb() throws Exception {
        try {
            // ── 写底账：模拟上一进程 IncidentPersistence 镜像产物（直接用 Mapper） ──
            seedIncident(ID_CLOSED, "judge-service", "队列深度 320 > 200 且爬升",
                    "CLOSED", "【复盘报告 · mock】根因：评测 worker 池饱和；处置：已重启", 700);
            seedStep(ID_CLOSED, "evidence", "evidences=[[首轮证据] logs[judge-service]: timeout killed]");
            seedStep(ID_CLOSED, "hypothesize", "hypotheses=1. 评测 worker 池饱和");
            seedStep(ID_CLOSED, "verify", "verdict=1 | 成立 | 与 worker 池饱和预期一致");
            seedStep(ID_CLOSED, "plan", "plan=RESTART | judge-worker-1 | HIGH | 恢复 worker 池");
            seedStep(ID_CLOSED, "awaitApproval", "outcome=AWAITING_APPROVAL");
            seedStep(ID_CLOSED, "pending", "outcome=AWAITING_APPROVAL");
            seedStep(ID_CLOSED, "execute", "actions=[MOCK: docker 未启用]");
            seedStep(ID_CLOSED, "retrospect", "outcome=CLOSED");
            final int closedSteps = 8;

            // ── 模拟重启：抹掉内存仓（JVM 重启后内存归零的等价物） ──
            wipeMemory();

            // ── 走分层读详情（IncidentService.get，与 GET /api/incidents/{id} 同一入口） ──
            Map<String, Object> view = incidentService.get(ID_CLOSED);
            assertNotNull(view, "重启后历史事件必须从 DB 查得到");

            // 字段名与内存快照同形（前端零改动红线）
            assertEquals(ID_CLOSED, ((Number) view.get(IncidentState.INCIDENT_ID)).longValue());
            assertEquals("judge-service", view.get(IncidentState.SERVICE));
            assertEquals("队列深度 320 > 200 且爬升", view.get(IncidentState.ALERT));
            assertEquals("CLOSED", view.get(IncidentState.OUTCOME));
            assertEquals("【复盘报告 · mock】根因：评测 worker 池饱和；处置：已重启",
                    view.get(IncidentState.REPORT));
            assertEquals(700, view.get(IncidentState.TOTAL_TOKENS));

            // timeline：t_graph_step 按 incident_id 还原，条目形状 {at,node,digest}
            List<?> timeline = (List<?>) view.get("timeline");
            assertNotNull(timeline, "详情必须带 timeline 字段");
            assertEquals(closedSteps, timeline.size(), "timeline 条数 = 落库的节点步数");
            Object first = timeline.get(0);
            assertTrue(first instanceof Map<?, ?>, "timeline 条目必须是 Map（对齐 JSON 形态）");
            Map<?, ?> entry = (Map<?, ?>) first;
            assertEquals("evidence", entry.get("node"), "节点名与内存时间线逐字一致");
            Object at = entry.get("at");
            assertTrue(at instanceof Number && ((Number) at).longValue() > 0,
                    "at 必须是 epoch millis（前端 new Date(at) 直接可解析）");
            assertTrue(String.valueOf(entry.get("digest")).contains("首轮证据"),
                    "digest 是节点产出摘要原文");
            Map<?, ?> last = (Map<?, ?>) timeline.get(timeline.size() - 1);
            assertEquals("retrospect", last.get("node"), "timeline 按落库顺序（即写入序）排列");
        } finally {
            cleanup(ID_CLOSED);
        }
    }

    @Test
    @DisplayName("AWAITING_APPROVAL 事件重启后从 DB 恢复展示；列表分层合并含历史行")
    void awaitingApprovalRestoredAndListMergesHistory() throws Exception {
        try {
            seedIncident(ID_AWAITING, "judge-service", "队列堆积（重启恢复展示）",
                    "AWAITING_APPROVAL", null, 600);
            seedStep(ID_AWAITING, "evidence", "evidences=[[首轮证据] ...]");
            seedStep(ID_AWAITING, "hypothesize", "hypotheses=1. 假设");
            seedStep(ID_AWAITING, "verify", "verdict=1 | 成立");
            seedStep(ID_AWAITING, "plan", "plan=RESTART | judge-worker-1 | HIGH | 理由");
            seedStep(ID_AWAITING, "awaitApproval", "outcome=AWAITING_APPROVAL");
            seedStep(ID_AWAITING, "pending", "outcome=AWAITING_APPROVAL");
            final int awaitingSteps = 6;

            wipeMemory();

            // 详情：AWAITING_APPROVAL 重启后查得到（展示恢复，续跑不在本轮）
            Map<String, Object> view = incidentService.get(ID_AWAITING);
            assertNotNull(view, "AWAITING_APPROVAL 事件重启后必须可展示");
            assertEquals("AWAITING_APPROVAL", view.get(IncidentState.OUTCOME));
            assertEquals("", view.get(IncidentState.REPORT), "root_cause 为 NULL 时给空串（前端兜底）");
            assertEquals(awaitingSteps, ((List<?>) view.get("timeline")).size());

            // status 过滤：DB 历史行同样参与（合并进列表后能被过滤出来）
            List<Map<String, Object>> awaiting = incidentService.list("AWAITING_APPROVAL", 0, 100);
            assertTrue(awaiting.stream().anyMatch(s ->
                            ((Number) s.getOrDefault(IncidentState.INCIDENT_ID, 0L)).longValue() == ID_AWAITING),
                    "DB 历史行必须出现在 status 过滤后的列表里");

            // 不带过滤的全量列表：历史行在、且按事件号升序（分页参数同原契约）
            List<Map<String, Object>> all = incidentService.list(null, 0, 100);
            long prev = -1;
            boolean sawHistory = false;
            for (Map<String, Object> row : all) {
                long id = ((Number) row.getOrDefault(IncidentState.INCIDENT_ID, 0L)).longValue();
                assertTrue(id >= prev, "列表必须按事件号升序");
                prev = id;
                if (id == ID_AWAITING) {
                    sawHistory = true;
                    // 列表行不带 timeline（契约既有约定），但 steps 已由分组计数补齐
                    assertTrue(!row.containsKey("timeline"), "列表行不携带 timeline");
                    assertEquals(awaitingSteps, row.get(IncidentState.STEPS), "列表行 steps = t_graph_step 行数");
                }
            }
            assertTrue(sawHistory, "全量列表必须含 DB 历史行");

            // DB 视图不回灌内存：approve 对 DB 恢复的事件仍报"不存在"（续跑不在本轮，
            // 图重放语义不被 DB 视图污染——视图里没有完整 State 通道）
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> incidentService.approve(ID_AWAITING, true));
        } finally {
            cleanup(ID_AWAITING);
        }
    }

    @Test
    @DisplayName("分层读边界：内存命中的事件走内存，不被 DB 底账覆盖")
    void memoryHitTakesPrecedenceOverDb() throws Exception {
        try {
            // DB 里放一份"滞后镜像"（steps 故意写错值，若读了 DB 就会露馅）
            seedIncident(ID_MEMORY, "judge-service", "内存优先对照", "CLOSED",
                    "DB 滞后镜像", 1);
            seedStep(ID_MEMORY, "evidence", "DB 侧假摘要");

            // 内存里放真相（steps=13、内存时间线登记处一条真摘要）
            Map<String, Object> live = new java.util.HashMap<>();
            live.put(IncidentState.INCIDENT_ID, ID_MEMORY);
            live.put(IncidentState.OUTCOME, "CLOSED");
            live.put(IncidentState.REPORT, "内存真相");
            live.put(IncidentState.STEPS, 13);
            putIntoMemory(ID_MEMORY, live);
            progressListener.onNodeComplete(ID_MEMORY, "retrospect", Map.of("mark", "内存侧真摘要"));

            Map<String, Object> view = incidentService.get(ID_MEMORY);
            assertEquals("内存真相", view.get(IncidentState.REPORT),
                    "内存命中必须优先于 DB 滞后镜像");
            assertEquals(13, view.get(IncidentState.STEPS),
                    "steps 来自内存快照而非 DB 行");
            // 时间线也走内存登记处（TimelineEvent record）：DB 侧同号节点行不得渗入
            List<?> memoryTimeline = (List<?>) view.get("timeline");
            assertEquals(1, memoryTimeline.size(), "时间线来自 NodeProgressListener 登记处");
            com.sentryagent.service.NodeProgressListener.TimelineEvent memEntry =
                    (com.sentryagent.service.NodeProgressListener.TimelineEvent) memoryTimeline.get(0);
            assertEquals("retrospect", memEntry.node(), "节点名与内存登记处一致");
            assertTrue(memEntry.digest().contains("内存侧真摘要"),
                    "timeline 必须由本进程登记处提供（运行中事件事实源是内存）");

            // DB 侧同号行不重复出现在列表里（合并去重：内存优先）
            List<Map<String, Object>> all = incidentService.list(null, 0, 100);
            long hits = all.stream().filter(s ->
                    ((Number) s.getOrDefault(IncidentState.INCIDENT_ID, 0L)).longValue() == ID_MEMORY).count();
            assertEquals(1, hits, "同号事件在列表里只出现一次（内存优先去重）");
        } finally {
            cleanup(ID_MEMORY);
        }
    }

    @Test
    @DisplayName("查无此事件（内存+DB 都没有）→ get 返回 null（404 语义不变）")
    void missingIncidentStillNull() {
        wipeMemory();
        assertNull(incidentService.get(998L * 1000 + 777),
                "内存与 DB 都查不到必须返回 null（控制器转 404）");
    }

    /** 直接用 Mapper 写一条事件底账（等价 IncidentPersistence.saveIncident 的镜像产物） */
    private void seedIncident(long id, String service, String alert,
                              String outcome, String rootCause, int tokens) {
        IncidentEntity e = new IncidentEntity();
        e.setId(id);
        e.setService(service);
        e.setAlertSummary(alert);
        e.setOutcome(outcome);
        e.setRootCause(rootCause);
        e.setTotalTokens(tokens);
        e.setStartedAt(LocalDateTime.now().minusMinutes(5));
        e.setClosedAt("CLOSED".equals(outcome) || "ESCALATED".equals(outcome)
                ? LocalDateTime.now() : null);
        incidentMapper.insert(e);
    }

    /** 直接用 Mapper 写一条节点时间线底账（等价 IncidentPersistence.saveStep 的镜像产物） */
    private void seedStep(long incidentId, String node, String digest) {
        GraphStepEntity s = new GraphStepEntity();
        s.setIncidentId(incidentId);
        s.setNodeName(node);
        s.setDigest(digest);
        s.setAt(LocalDateTime.now().minusMinutes(3));
        graphStepMapper.insert(s);
    }

    /** 模拟重启：清空 IncidentService 内存仓（等价 JVM 重启后内存归零） */
    @SuppressWarnings("unchecked")
    private void wipeMemory() {
        Map<Long, Map<String, Object>> store =
                (Map<Long, Map<String, Object>>) ReflectionTestUtils.getField(incidentService, "incidents");
        store.clear();
    }

    /** 向内存仓塞一条快照（分层读对照用） */
    @SuppressWarnings("unchecked")
    private void putIntoMemory(long id, Map<String, Object> snapshot) {
        Map<Long, Map<String, Object>> store =
                (Map<Long, Map<String, Object>>) ReflectionTestUtils.getField(incidentService, "incidents");
        store.put(id, snapshot);
    }

    /** 清理本用例插入的底账行（只删自己的 ID 段，不碰其他数据） */
    private void cleanup(long id) {
        incidentMapper.delete(new LambdaQueryWrapper<IncidentEntity>()
                .eq(IncidentEntity::getId, id));
        graphStepMapper.delete(new LambdaQueryWrapper<GraphStepEntity>()
                .eq(GraphStepEntity::getIncidentId, id));
    }
}
