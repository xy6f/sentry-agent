package com.sentryagent.service;

import com.sentryagent.graph.IncidentState;
import com.sentryagent.tool.ApprovalRegistry;
import com.sentryagent.tool.EvidenceTools;
import com.sentryagent.tool.DockerExecutor;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过期令牌改判单测（不足清单第 2 项的 approve 侧验收）：
 * <ol>
 *   <li>工具层：过期令牌被 REFUSED，文案带 EXPIRED 标记（与"无令牌"区分）；</li>
 *   <li>编排层：approve(true) 续跑后若 execute 里发生了 EXPIRED 型拒绝，
 *       事件必须改判 ESCALATED + report="审批令牌过期，自动转人工"——
 *       approve=false 的"无令牌人工否决"语义不得被混用；</li>
 *   <li>对照组：正常 REFUSED（无令牌/错令牌）不改判，事件照常走 CLOSED。
 *       编排层用 Mockito mock CompiledGraph（invoke 返回带 REFUSED 动作的终态），
 *       不跑真图、不起 Spring、不碰读路径。</li>
 * </ol>
 */
class ApprovalExpiredEscalateTest {

    /** 构造一个"图执行结果"状态：actions 里含指定拒绝文案，其余通道最小化 */
    private static Map<String, Object> stateWithActions(List<String> actions) {
        Map<String, Object> data = new HashMap<>();
        data.put(IncidentState.INCIDENT_ID, 7L);
        data.put(IncidentState.OUTCOME, "CLOSED");
        data.put(IncidentState.ACTIONS, actions);
        data.put(IncidentState.REPORT, "复盘报告");
        return data;
    }

    /** 组装被测 IncidentService：mock 图（invoke 原样回传入参对应的终态）+ 真实注册表 */
    @SuppressWarnings("unchecked")
    private IncidentService serviceWithMockGraph(ApprovalRegistry registry,
                                                 Map<String, Object> graphResult) {
        CompiledGraph<IncidentState> graph = Mockito.mock(CompiledGraph.class);
        Mockito.when(graph.invoke(Mockito.anyMap()))
                .thenReturn(java.util.Optional.of(new IncidentState(graphResult)));
        com.sentryagent.service.NodeProgressListener progress =
                new com.sentryagent.service.NodeProgressListener();
        ObjectProvider<com.sentryagent.persist.IncidentPersistence> provider =
                Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(null);
        // 读路径落库 provider 同款 mock：单测不起 Spring、不碰 DB（get/list 仍走纯内存）
        ObjectProvider<com.sentryagent.persist.IncidentQueryService> queryProvider =
                Mockito.mock(ObjectProvider.class);
        Mockito.when(queryProvider.getIfAvailable()).thenReturn(null);
        return new IncidentService(graph, registry, progress, null, provider, queryProvider);
    }

    @Test
    @DisplayName("工具层：过期令牌 REFUSED 文案带 EXPIRED 标记，且保留'不得重试'指令")
    void toolRefusalCarriesExpiredMarker() throws InterruptedException {
        ApprovalRegistry registry = new ApprovalRegistry(1L);
        EvidenceTools tools = new EvidenceTools(registry, new DockerExecutor());
        String token = registry.issue(7L);
        long deadline = System.currentTimeMillis() + 2;
        while (System.currentTimeMillis() < deadline) {
            Thread.yield();   // 推进时钟越过 expireAt
        }
        String refused = tools.restartContainer("judge-worker-1", token, 7L);
        assertTrue(refused.startsWith("REFUSED"), "过期令牌必须拒绝: " + refused);
        assertTrue(refused.contains("EXPIRED"), "拒绝文案必须带 EXPIRED 标记（approve 侧识别用）: "
                + refused);
        assertTrue(refused.contains("不得重试"), "拒绝文案保留'不得重试'指令: " + refused);
        // 对照组：无令牌的普通拒绝不带 EXPIRED（"无令牌"语义不被污染）
        String plain = tools.restartContainer("judge-worker-1", null, 7L);
        assertTrue(plain.startsWith("REFUSED") && !plain.contains("EXPIRED"),
                "无令牌拒绝不得混入 EXPIRED 标记: " + plain);
    }

    @Test
    @DisplayName("编排层：execute 出现 EXPIRED 型拒绝 → approve 改判 ESCALATED+指定 report")
    void approveRewritesExpiredRefusalToEscalated() {
        ApprovalRegistry registry = new ApprovalRegistry(600_000L);
        Map<String, Object> graphResult = stateWithActions(List.of(
                "[RESTART|judge-worker-1|HIGH] REFUSED: 审批令牌已过期（EXPIRED），审批窗口已关闭，不得重试，需重新走人工审批"));
        IncidentService service = serviceWithMockGraph(registry, graphResult);
        // 先造一个 AWAITING_APPROVAL 快照（模拟第一轮停在审批闸口）
        Map<String, Object> awaiting = new HashMap<>();
        awaiting.put(IncidentState.INCIDENT_ID, 7L);
        awaiting.put(IncidentState.OUTCOME, "AWAITING_APPROVAL");
        awaiting.put(IncidentState.PLAN, "RESTART | judge-worker-1 | HIGH | test");
        seedIncident(service, awaiting);

        Map<String, Object> result = service.approve(7L, true);
        assertEquals("ESCALATED", result.get(IncidentState.OUTCOME),
                "过期令牌场景必须改判 ESCALATED");
        assertEquals("审批令牌过期，自动转人工", result.get(IncidentState.REPORT),
                "report 必须是过期专用文案（与人工否决文案区分）");
    }

    @Test
    @DisplayName("对照组：普通 REFUSED（无令牌型）不改判，事件保持 CLOSED 闭环")
    void plainRefusalStaysClosed() {
        ApprovalRegistry registry = new ApprovalRegistry(600_000L);
        Map<String, Object> graphResult = stateWithActions(List.of(
                "[RESTART|judge-worker-1|HIGH] REFUSED: 该操作未经人工审批（令牌无效或已使用），不得重试"));
        IncidentService service = serviceWithMockGraph(registry, graphResult);
        Map<String, Object> awaiting = new HashMap<>();
        awaiting.put(IncidentState.INCIDENT_ID, 8L);
        awaiting.put(IncidentState.OUTCOME, "AWAITING_APPROVAL");
        awaiting.put(IncidentState.PLAN, "RESTART | judge-worker-1 | HIGH | test");
        seedIncident(service, awaiting);

        Map<String, Object> result = service.approve(8L, true);
        assertEquals("CLOSED", result.get(IncidentState.OUTCOME),
                "普通 REFUSED 不是过期，不得误改判 ESCALATED");
    }

    /** 反射注入快照：IncidentService 的 incidents 仓是私有 Map，单测直接 seed 避免跑首轮图 */
    @SuppressWarnings("unchecked")
    private static void seedIncident(IncidentService service, Map<String, Object> snapshot) {
        Map<Long, Map<String, Object>> store =
                (Map<Long, Map<String, Object>>) org.springframework.test.util.ReflectionTestUtils
                        .getField(service, "incidents");
        long id = ((Number) snapshot.get(IncidentState.INCIDENT_ID)).longValue();
        store.put(id, snapshot);
    }
}
