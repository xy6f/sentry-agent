package com.sentryagent;

import com.sentryagent.graph.IncidentState;
import com.sentryagent.service.IncidentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * token 用量统计的端到端验收（README 遗留清单第 2 项，交付清单第 4 条配套）：
 *
 * <p>记账口径（mock 链路）：MockDiagnoserGateway.TOKENS_PER_CALL = 100，即网关每完成
 * 一次模型调用记 100（跳过分支不发起推理、不记账）。图上发起推理的节点为
 * evidence/hypothesize/verify/plan/retrospect，各节点用"两读数法"（入口/出口各读一次
 * 台账，差值写入 TOTAL_TOKENS 累加通道）——所以 totalTokens 恰等于"实际发生的推理
 * 调用次数 × 100"，断言写死具体数值，能同时抓"该计数的没计"与"不该计的计了"
 * （如幂等重放虚增）两类回归。本类断言全链累加：第一轮 6 次调用=600，
 * approve 重放只多 retrospect(1) → 700，两轮之间不虚增。</p>
 *
 * <p>预算护栏分支（budget=50 强制 escalate）见 {@link TokenBudgetGuardTest}；
 * 路由级纯函数断言见 IncidentRoutersTest——两层保险，与 steps 护栏的测试布局同款。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TokenUsageAccountingTest {

    /** 从创建响应 JSON 里抠 incidentId（与 IncidentEndToEndMockTest 同款解析） */
    private static final Pattern ID_JSON = Pattern.compile("\"incidentId\"\\s*:\\s*(\\d+)");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IncidentService incidentService;

    private static long extractId(MvcResult res) throws Exception {
        Matcher m = ID_JSON.matcher(res.getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertTrue(m.find(), "创建响应必须含 incidentId");
        return Long.parseLong(m.group(1));
    }

    @Test
    @DisplayName("mock 全链：第一轮 6 次调用=600，approve 重放只 +100=700，中途不虚增")
    void totalTokensAccumulatesAcrossTwoRuns() throws Exception {
        MvcResult created = mvc.perform(MockMvcRequestBuilders.post("/api/incidents")
                        .contentType("application/json")
                        .content("{\"service\":\"judge-service\",\"summary\":\"token accounting check\"}"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.outcome")
                        .value("AWAITING_APPROVAL"))
                .andReturn();
        long id = extractId(created);

        // ── 第一轮账本：evidence(100) + hypothesize(100) + verify(二次取证2×100 + 判定100=400)
        //    + plan(100) = 6 次推理调用 → totalTokens 恰为 600 ──
        Map<String, Object> first = incidentService.rawSnapshot(id);
        assertEquals(600, first.get(IncidentState.TOTAL_TOKENS),
                "第一轮 6 次模型调用（取证/假设/二次取证×2/判定/计划）×100 = 600");
        assertEquals("AWAITING_APPROVAL", first.get(IncidentState.OUTCOME));

        // ── 第二轮（approve 幂等重放）：前四节点全跳过（零推理零记账），只多 retrospect
        //    一次调用 → 700。若重放虚增，600→700 之间会多出 evidence/hypothesize 等账目 ──
        mvc.perform(MockMvcRequestBuilders.post("/api/incidents/" + id + "/decision")
                        .contentType("application/json")
                        .content("{\"approve\":true}"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.outcome").value("CLOSED"));

        Map<String, Object> second = incidentService.rawSnapshot(id);
        assertEquals(700, second.get(IncidentState.TOTAL_TOKENS),
                "approve 重放只应新增 retrospect 一次调用（600+100=700），跳过分支不得虚增");
        // 详情接口可见性：totalTokens 字段对外（GET /api/incidents/{id} 同源快照）
        Map<String, Object> view = incidentService.get(id);
        assertEquals(700, view.get(IncidentState.TOTAL_TOKENS));
    }
}
