package com.sentryagent.tool;

import com.sentryagent.metrics.HttpEndpointMetricsProvider;
import com.sentryagent.metrics.MockMetricsProvider;
import com.sentryagent.metrics.PrometheusMetricsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标源切换逻辑单测（不足清单清单第 3 项的"选择开关"）：
 * EvidenceTools 按 sentry.metrics.provider 从 List&lt;MetricsProvider&gt; 里按名挑用，
 * 配置缺失默认 mock，配置值不识别回落 mock（默认兜底，取证链路不因配置手误起不来）。
 *
 * <p>纯单测不起 Spring：三实现手工组 List 注入，@Value 字段用
 * ReflectionTestUtils 灌测试值（同 ApprovalTokenGuardTest 的立场）。</p>
 */
class MetricsProviderSwitchTest {

    private EvidenceTools tools;

    @BeforeEach
    void setUp() {
        tools = new EvidenceTools(new ApprovalRegistry(), new DockerExecutor(),
                List.of(new MockMetricsProvider(),
                        new HttpEndpointMetricsProvider("http://127.0.0.1:1/api/judge/status",
                                RestClient.builder()),
                        new PrometheusMetricsProvider("http://127.0.0.1:1", RestClient.builder())));
        ReflectionTestUtils.setField(tools, "metricsDefaultQuery", "up");
        ReflectionTestUtils.setField(tools, "logsDir", "./target/unused-logs");
        ReflectionTestUtils.setField(tools, "logsTailLines", 10);
    }

    @Test
    @DisplayName("provider=mock：挑中 Mock 实现，getMetrics 返回剧本文案")
    void selectMock() {
        ReflectionTestUtils.setField(tools, "metricsProviderName", "mock");
        assertInstanceOf(MockMetricsProvider.class, tools.currentProvider());
        String out = tools.getMetrics("judge-service");
        assertTrue(out.startsWith("metrics[judge-service] = "), out);
        assertTrue(out.contains("cpu=95%"), out);
    }

    @Test
    @DisplayName("配置缺失（null/空串）：默认回落 mock 兜底")
    void missingConfigDefaultsToMock() {
        ReflectionTestUtils.setField(tools, "metricsProviderName", null);
        assertInstanceOf(MockMetricsProvider.class, tools.currentProvider());
        ReflectionTestUtils.setField(tools, "metricsProviderName", "   ");
        assertInstanceOf(MockMetricsProvider.class, tools.currentProvider());
    }

    @Test
    @DisplayName("provider=http：挑中 Http 实现；端点不可达时降级为'查询失败'文本")
    void selectHttpAndDegradeOnUnreachable() {
        ReflectionTestUtils.setField(tools, "metricsProviderName", "http");
        assertInstanceOf(HttpEndpointMetricsProvider.class, tools.currentProvider());
        String out = assertDoesNotThrow(() -> tools.getMetrics("judge-service"));
        assertTrue(out.startsWith("metrics[judge-service]"), out);
        assertTrue(out.contains("查询失败"), "http 源不可达必须降级: " + out);
    }

    @Test
    @DisplayName("provider=prometheus：挑中 Prometheus 实现；端点不可达时同样降级")
    void selectPrometheusAndDegradeOnUnreachable() {
        ReflectionTestUtils.setField(tools, "metricsProviderName", "prometheus");
        assertInstanceOf(PrometheusMetricsProvider.class, tools.currentProvider());
        String out = assertDoesNotThrow(() -> tools.getMetrics("judge-service"));
        assertTrue(out.startsWith("metrics[judge-service]"), out);
        assertTrue(out.contains("查询失败"), "prometheus 源不可达必须降级: " + out);
    }

    @Test
    @DisplayName("配置值不识别（如手误 'prom'）：回落 mock，getMetrics 照常产出剧本")
    void unknownValueFallsBackToMock() {
        ReflectionTestUtils.setField(tools, "metricsProviderName", "prom");
        assertInstanceOf(MockMetricsProvider.class, tools.currentProvider());
        String out = tools.getMetrics("judge-service");
        assertTrue(out.contains("queueDepth=320"), out);
    }
}
