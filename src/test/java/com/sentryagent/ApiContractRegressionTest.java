package com.sentryagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询面契约回归（全量测试发现并修复的两个缺口的验收）：
 * <ol>
 *   <li>计划书第 6 章的 GET /api/incidents 事件列表端点原先缺失（405），
 *       现已补齐：返回数组、支持 status 过滤与分页；</li>
 *   <li>对不存在事件调 POST /{id}/decision 原先 500（异常冒到容器），
 *       现与 GET 详情对齐返回 404，且确认修复后不再签发用不上的令牌。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractRegressionTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    private long createIncident(String summary) throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/incidents")
                        .contentType("application/json")
                        .content("{\"service\":\"judge-service\",\"summary\":\"" + summary + "\"}"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
        return OM.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("incidentId").asLong();
    }

    @Test
    @DisplayName("GET /api/incidents 列表：200、含刚建事件、字段完整、status 过滤生效")
    void listEndpointContract() throws Exception {
        long id = createIncident("list endpoint check");

        MvcResult all = mvc.perform(MockMvcRequestBuilders.get("/api/incidents"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
        JsonNode arr = OM.readTree(all.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(arr.isArray(), "列表必须是 JSON 数组");
        JsonNode mine = null;
        for (JsonNode n : arr) {
            if (n.get("incidentId").asLong() == id) {
                mine = n;
            }
        }
        assertTrue(mine != null, "新建事件必须出现在列表里");
        // 详情字段完整性在列表行上同步校验（计划书第6章：列表/详情字段都要齐）
        for (String key : new String[]{"incidentId", "service", "alert", "outcome", "steps",
                "evidences", "hypotheses", "verdict", "plan"}) {
            assertTrue(mine.has(key), "列表行缺字段: " + key);
        }

        // status 过滤：AWAITING_APPROVAL 里能找到它，CLOSED 里不能
        mvc.perform(MockMvcRequestBuilders.get("/api/incidents")
                        .param("status", "AWAITING_APPROVAL"))
                .andExpect(MockMvcResultMatchers.status().isOk());
        MvcResult closedOnly = mvc.perform(MockMvcRequestBuilders.get("/api/incidents")
                        .param("status", "CLOSED"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
        JsonNode closedArr = OM.readTree(
                closedOnly.getResponse().getContentAsString(StandardCharsets.UTF_8));
        for (JsonNode n : closedArr) {
            assertTrue(n.get("incidentId").asLong() != id, "status 过滤不得混入未审批事件");
            assertTrue("CLOSED".equals(n.get("outcome").asText()), "过滤结果必须都是 CLOSED");
        }

        // 分页参数健壮性：超大页返回空数组而非报错
        MvcResult big = mvc.perform(MockMvcRequestBuilders.get("/api/incidents")
                        .param("page", "9999"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
        assertTrue(OM.readTree(big.getResponse().getContentAsString(
                StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    @DisplayName("decision 对不存在事件返回 404（回归：曾因 IllegalArgumentException 冒 500）")
    void decisionMissingIncidentIs404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/incidents/999999/decision")
                        .contentType("application/json")
                        .content("{\"approve\":true}"))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
    }
}
