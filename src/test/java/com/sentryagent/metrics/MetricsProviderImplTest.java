package com.sentryagent.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 三种指标源实现的单元测试（不足清单第 3 项，测试计划 F 的延伸）：
 * 每个实现至少一个"正常"用例 + 一个"降级/健壮"用例。
 * 两个网络实现用 MockRestServiceServer.bindTo(RestClient.Builder) 拦截请求（零真网络）；
 * 失败语义 = provider 抛异常，由 EvidenceTools 统一翻译成"查询失败"降级文本
 * （EvidenceTools 层的降级断言见 ToolDegradationTest 与 MetricsProviderSwitchTest）。
 */
class MetricsProviderImplTest {

    // ---------- MockMetricsProvider（mock，默认兜底） ----------

    @Test
    @DisplayName("mock 正常：返回剧本文案（cpu=95%/queueDepth=320），name 与配置开关对齐")
    void mockReturnsScriptText() {
        MockMetricsProvider p = new MockMetricsProvider();
        assertEquals("mock", p.name());
        String out = p.query("judge-service", "queueDepth");
        assertTrue(out.contains("cpu=95%"), out);
        assertTrue(out.contains("queueDepth=320"), out);
    }

    @Test
    @DisplayName("mock 健壮（降级口径）：service/metric 为 null 也不抛、文案稳定")
    void mockNeverThrowsEvenWithNullArgs() {
        MockMetricsProvider p = new MockMetricsProvider();
        String out = assertDoesNotThrow(() -> p.query(null, null));
        assertFalse(out.isBlank());
        // 两次调用返回逐字相同——兜底实现的输出必须是确定性的
        assertEquals(out, p.query("", "   "));
    }

    // ---------- HttpEndpointMetricsProvider（http，原硬编码行为的配置化） ----------

    @Test
    @DisplayName("http 正常：GET 配置端点并原样返回响应体（JSON 明文）")
    void httpSuccessReturnsBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEndpointMetricsProvider p = new HttpEndpointMetricsProvider(
                "http://fake-target:18080/api/judge/status", builder);
        String body = "{\"queueDepth\":3,\"busyWorkers\":2,\"workers\":6}";
        server.expect(requestTo("http://fake-target:18080/api/judge/status"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertEquals(body, p.query("judge-service", "queueDepth"));
        server.verify();
    }

    @Test
    @DisplayName("http 降级：端点 5xx → 抛异常（由 EvidenceTools 翻译成'查询失败'）")
    void httpServerErrorThrows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpEndpointMetricsProvider p = new HttpEndpointMetricsProvider(
                "http://fake-target:18080/api/judge/status", builder);
        server.expect(requestTo("http://fake-target:18080/api/judge/status"))
                .andRespond(withServerError());
        RestClientException e = assertThrows(RestClientException.class,
                () -> p.query("judge-service", "queueDepth"));
        assertTrue(String.valueOf(e.getMessage()).contains("500"), e.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("http 配置解析：显式 http-url 优先；未配回落旧行为 {target-base-url}/api/judge/status")
    void resolveUrlExplicitWinsFallbackMatchesOldBehavior() {
        assertEquals("http://explicit:9999/health",
                HttpEndpointMetricsProvider.resolveUrl(" http://explicit:9999/health ",
                        "http://base:8080"));
        // 未配 http-url = 旧行为逐字对齐（尾部斜杠容错）
        assertEquals("http://base:8080/api/judge/status",
                HttpEndpointMetricsProvider.resolveUrl(null, "http://base:8080"));
        assertEquals("http://base:8080/api/judge/status",
                HttpEndpointMetricsProvider.resolveUrl("  ", "http://base:8080/"));
    }

    // ---------- PrometheusMetricsProvider（prometheus，复用 RuleEvaluator 正则） ----------

    private static final String PROM_RESULT_JSON =
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":"
                    + "[{\"metric\":{\"job\":\"judge\"},\"value\":[1737000000,\"0.85\"]}]}}";

    @Test
    @DisplayName("prometheus 正常：instant query 命中样本 → '指标 up = 0.85' 明文")
    void prometheusSuccessParsesValue() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PrometheusMetricsProvider p = new PrometheusMetricsProvider("http://prom:9090/", builder);
        server.expect(requestTo("http://prom:9090/api/v1/query?query=up"))
                .andRespond(withSuccess(PROM_RESULT_JSON, MediaType.APPLICATION_JSON));
        assertEquals("指标 up = 0.85（prometheus instant query）",
                p.query("judge-service", "up"));
        server.verify();
    }

    @Test
    @DisplayName("prometheus 空结果（降级口径一）：无样本不抛异常，返回'无样本'可推理文案")
    void prometheusEmptyResultReturnsNoSampleText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PrometheusMetricsProvider p = new PrometheusMetricsProvider("http://prom:9090", builder);
        server.expect(requestTo("http://prom:9090/api/v1/query?query=up"))
                .andRespond(withSuccess(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}",
                        MediaType.APPLICATION_JSON));
        String out = assertDoesNotThrow(() -> p.query("judge-service", "up"));
        assertTrue(out.contains("无样本"), out);
        assertTrue(out.contains("query=up"), out);
        server.verify();
    }

    @Test
    @DisplayName("prometheus 5xx（降级口径二）：抛异常且消息带 HTTP 状态（外层补'查询失败'前缀）")
    void prometheusServerErrorThrowsWithStatus() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PrometheusMetricsProvider p = new PrometheusMetricsProvider("http://prom:9090", builder);
        server.expect(requestTo("http://prom:9090/api/v1/query?query=up"))
                .andRespond(withServerError());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> p.query("judge-service", "up"));
        assertTrue(e.getMessage().contains("HTTP"), e.getMessage());
        // 消息刻意不带"查询失败"字样：外层 EvidenceTools 会统一加该前缀，避免重复
        assertFalse(e.getMessage().contains("查询失败"), e.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("prometheus metric 空白回落 'up'；表达式经 URL 编码直传完整 URI")
    void prometheusBlankMetricFallsBackToUpAndEncodesExpr() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PrometheusMetricsProvider p = new PrometheusMetricsProvider("http://prom:9090", builder);
        // 期望必须先全部声明再发请求（MockRestServiceServer 请求后不再受理 expect）
        // 期望一：metric 为空串 → 回落 "up"
        server.expect(requestTo("http://prom:9090/api/v1/query?query=up"))
                .andRespond(withSuccess(PROM_RESULT_JSON, MediaType.APPLICATION_JSON));
        // 期望二：带 {} 的 PromQL：URL 编码后直传（URI.create 不把 {} 当模板占位符）
        server.expect(requestTo("http://prom:9090/api/v1/query?query=up%7Bjob%3D%22judge%22%7D"))
                .andRespond(withSuccess(PROM_RESULT_JSON, MediaType.APPLICATION_JSON));
        assertEquals("指标 up = 0.85（prometheus instant query）", p.query("svc", "   "));
        assertEquals("指标 up{job=\"judge\"} = 0.85（prometheus instant query）",
                p.query("svc", "up{job=\"judge\"}"));
        server.verify();
    }
}
