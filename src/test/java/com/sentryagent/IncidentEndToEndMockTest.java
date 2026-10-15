package com.sentryagent;

import com.sentryagent.graph.IncidentState;
import com.sentryagent.service.IncidentService;
import com.sentryagent.service.NodeProgressListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端 mock 验收（llm.enabled=false 缺省，即 README 演示链路的自动化版本）：
 *
 * <p>第一轮 run：POST /api/incidents 建事件 → 图走完 取证→假设→验证(二次取证,判定成立)
 * →计划(RESTART HIGH)→审批闸口 → 无令牌 → pending → 终态 AWAITING_APPROVAL，
 * 断言证据/假设/判定/计划各通道都有内容且时间线记满 6 个节点。</p>
 *
 * <p>第二轮 run（审批续跑）：POST decision approve=true 签发一次性令牌 → 幂等重放：
 * 前四节点全部走"跳过"分支（模型零调用），闸口放行 → execute 真实消费令牌
 * restartContainer 得 OK → retrospect 出报告 → 终态 CLOSED。
 * 断言两轮 State 的 outcome/steps/actions/report 演进关系。</p>
 *
 * <p>附赠否决路径：approve=false 直接终结为 ESCALATED——审批的两种结局都在这张图上。</p>
 *
 * <p>方法顺序钉死（Order）：happy path 先跑，否决路径后跑且事件 id 全部从响应体
 * 动态解析——不依赖"上一个测试有没有跑过"的隐式共享状态。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncidentEndToEndMockTest {

    /** 从创建响应 JSON 里抠 incidentId 数字，两个测试各自取自己新建的事件 */
    private static final Pattern ID_JSON = Pattern.compile("\"incidentId\"\\s*:\\s*(\\d+)");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IncidentService incidentService;

    private static long extractId(MvcResult res) throws java.io.UnsupportedEncodingException {
        Matcher m = ID_JSON.matcher(res.getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertTrue(m.find(), "创建响应必须含 incidentId");
        return Long.parseLong(m.group(1));
    }

    @Test
    @Order(1)
    @DisplayName("create→AWAITING_APPROVAL，decision approve→CLOSED：两轮 run 状态断言")
    void createAwaitThenApproveClose() throws Exception {
        // ── 第一轮：建事件，图在审批闸口前停住 ──
        MvcResult created = mvc.perform(MockMvcRequestBuilders.post("/api/incidents")
                        .contentType("application/json")
                        .content("{\"service\":\"judge-service\",\"summary\":\"队列深度 320 > 200 且爬升\"}"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.outcome")
                        .value("AWAITING_APPROVAL"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.plan")
                        .value(org.hamcrest.Matchers.containsString("RESTART")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.verdict")
                        .value(org.hamcrest.Matchers.containsString("成立")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.evidences",
                        org.hamcrest.Matchers.hasSize(3)))   // 首轮 1 条 + verify 二次取证 2 条
                .andExpect(MockMvcResultMatchers.jsonPath("$.steps").value(6))
                .andReturn();
        long id = extractId(created);

        Map<String, Object> first = incidentService.rawSnapshot(id);
        assertNotNull(first);
        // 二次取证已把"补充证据"进 appender 通道：回环路径上 verify 真的产生过新信息
        List<?> evidences = (List<?>) first.get(IncidentState.EVIDENCES);
        assertTrue(evidences.stream().anyMatch(o -> String.valueOf(o).contains("[补充证据]")),
                "verify 节点的定向取证必须累积进证据通道");
        List<NodeProgressListener.TimelineEvent> tl1 = incidentService.timeline(id);
        assertEquals(6, tl1.size()); // evidence/hypothesize/verify/plan/awaitApproval/pending
        assertEquals("pending", tl1.get(5).node());

        // ── 第二轮：批准 → 令牌注入 → 幂等重放到 CLOSED ──
        mvc.perform(MockMvcRequestBuilders.post("/api/incidents/" + id + "/decision")
                        .contentType("application/json")
                        .content("{\"approve\":true}"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.outcome").value("CLOSED"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.actions[0]",
                        org.hamcrest.Matchers.containsString(
                                "OK: container judge-worker-1 restart issued")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.report",
                        org.hamcrest.Matchers.containsString("复盘报告")));

        Map<String, Object> second = incidentService.rawSnapshot(id);
        assertEquals("CLOSED", second.get(IncidentState.OUTCOME));
        // 重放段又走 7 个节点（4 跳过 + awaitApproval + execute + retrospect），步数 6→13 严格增长
        assertEquals(13, second.get(IncidentState.STEPS));
        // 详情接口（含 timeline 合并视图）：第二轮后共 13 条进度
        Map<String, Object> view = incidentService.get(id);
        assertEquals(13, ((List<?>) view.get("timeline")).size());
        // 幂等重放证据：两轮之间证据通道没再涨（跳过分支复用旧产出，模型零调用）
        assertEquals(3, ((List<?>) second.get(IncidentState.EVIDENCES)).size());
    }

    @Test
    @Order(2)
    @DisplayName("decision approve=false：事件直接终结 ESCALATED，绝不进入 execute")
    void rejectTerminatesWithoutExecution() throws Exception {
        MvcResult created = mvc.perform(MockMvcRequestBuilders.post("/api/incidents")
                        .contentType("application/json")
                        .content("{\"service\":\"judge-service\",\"summary\":\"队列堆积（拟走否决路径）\"}"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.outcome")
                        .value("AWAITING_APPROVAL"))
                .andReturn();
        long id = extractId(created);

        mvc.perform(MockMvcRequestBuilders.post("/api/incidents/" + id + "/decision")
                        .contentType("application/json")
                        .content("{\"approve\":false}"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.outcome").value("ESCALATED"));

        Map<String, Object> snap = incidentService.rawSnapshot(id);
        assertTrue(String.valueOf(snap.get(IncidentState.REPORT)).contains("人工否决"));
        // 断言修正（P1 修复配套）：意图是"未执行任何动作"。旧写法 containsKey(ACTIONS)
        // 是搭了"否决整体替换快照"缺陷的便车——appender 通道本就会把 actions 初始化为空列表。
        // 正确断言：actions 为空（而非键不存在）。
        Object actions = snap.get(IncidentState.ACTIONS);
        assertTrue(actions == null || ((java.util.List<?>) actions).isEmpty(),
                "被否决的事件绝不能产生动作执行记录");
        // P1 回归钉：否决只改 outcome/report，诊断过程数据必须保留
        // （旧实现整体替换快照导致 evidences/hypotheses 全丢，前端 ESCALATED 左栏空白）。
        Object evidences = snap.get(IncidentState.EVIDENCES);
        assertTrue(evidences instanceof java.util.List<?> && !((java.util.List<?>) evidences).isEmpty(),
                "否决后证据链必须保留在快照里");
        assertTrue(snap.containsKey(IncidentState.HYPOTHESES), "否决后假设必须保留");
    }
}
