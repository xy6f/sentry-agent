package com.sentryagent.tool;

import com.sentryagent.metrics.HttpEndpointMetricsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读工具降级链路单测（测试计划 F）：
 * 工具失败必须变成"可推理的信息"而不是异常——取证循环不能被一次网络失败炸掉。
 * 本类补上 ApprovalTokenGuardTest/SeedLogsRoundTripTest 之外的最后一角：
 * getMetrics 目标不可达时的降级文案。
 *
 * <p>不足清单第 3 项改造后的形态：指标源装配 http 实现并指向恒不可达端口，
 * 降级语义不变——provider 抛异常，EvidenceTools 统一翻译成"查询失败"文本。</p>
 */
class ToolDegradationTest {

    private EvidenceTools tools;

    @BeforeEach
    void setUp() {
        // 指标源=http 实现，端点指到端口 1（恒不可达）：制造"被监控系统没起"的真实网络故障形态
        tools = new EvidenceTools(new ApprovalRegistry(), new DockerExecutor(),
                List.of(new HttpEndpointMetricsProvider("http://127.0.0.1:1/api/judge/status",
                        RestClient.builder())));
        ReflectionTestUtils.setField(tools, "metricsProviderName", "http");
        ReflectionTestUtils.setField(tools, "metricsDefaultQuery", "up");
        ReflectionTestUtils.setField(tools, "logsDir", "./target/unused-logs");
        ReflectionTestUtils.setField(tools, "logsTailLines", 10);
    }

    @Test
    @DisplayName("getMetrics 目标不可达：不抛异常，返回'查询失败'降级文本")
    void metricsUnreachableDegrades() {
        String out = assertDoesNotThrow(() -> tools.getMetrics("judge-service"));
        assertTrue(out.startsWith("metrics[judge-service]"), out);
        assertTrue(out.contains("查询失败"), out);
    }

    @Test
    @DisplayName("listRecentDeploys/getContainerStatus 对穿越 service 名同样清洗后回显")
    void readToolsSanitizeEverywhere() {
        // 清洗不止在日志读取一处——所有回显不可信参数的读工具口径一致
        assertTrue(tools.listRecentDeploys("../etc/passwd")
                .startsWith("deploys[.._etc_passwd]"));
        assertTrue(tools.getContainerStatus("../etc/passwd")
                .startsWith("containers[.._etc_passwd]"));
    }
}
