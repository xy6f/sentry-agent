package com.sentryagent.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Watchman 真链路契约回归 + 判定逻辑单测（watchman 真机验证时发现并修复的 bug 全部钉在这里）。
 *
 * <p>不用 @SpringBootTest：Watchman 的 @Scheduled 在测试里直接手动调 poll() 更可控；
 * 依赖的 IncidentService 用 Mockito 替身只记录调用，不跑真图。</p>
 *
 * <p>HTTP 目标用 JDK 自带的 com.sun.net.httpserver.HttpServer 起在随机端口，
 * 零新增依赖地覆盖"真实响应解析/401/不可达"三种探针形态。</p>
 */
class WatchmanTest {

    private IncidentService incidentService;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        incidentService = mock(IncidentService.class);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 起一个单端点桩服务：固定状态码 + 固定响应体 */
    private String stubServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/judge/status", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Watchman watchmanAt(String baseUrl, int queueThreshold,
                                int consecutiveTimes, long cooldownMs) {
        Watchman w = new Watchman(incidentService);
        ReflectionTestUtils.setField(w, "targetBaseUrl", baseUrl);
        ReflectionTestUtils.setField(w, "queueThreshold", queueThreshold);
        ReflectionTestUtils.setField(w, "consecutiveTimes", consecutiveTimes);
        ReflectionTestUtils.setField(w, "cooldownMs", cooldownMs);
        return w;
    }

    // ---------- 响应解析：真机契约 bug（queueDepth vs queued） ----------

    @Test
    void evaluateBody_realMiniJudgeFieldQueued_belowThreshold_isNormal() {
        // mini-judge 真实 /api/judge/status 回包形态（watchman 真机验证抓到的实录）
        Watchman w = watchmanAt("http://unused", 200, 3, 300000);
        assertThat(w.evaluateBody("{\"code\":0,\"msg\":\"ok\",\"data\":"
                + "{\"queued\":0,\"running\":0,\"workerBusy\":0,\"workerCapacity\":4}}"))
                .isNull();
    }

    @Test
    void evaluateBody_realMiniJudgeFieldQueued_aboveThreshold_isAbnormal() {
        // 回归钉：修复前这里返回"未含 queueDepth 字段"异常——健康服务被误报的根因
        Watchman w = watchmanAt("http://unused", 200, 3, 300000);
        String reason = w.evaluateBody("{\"code\":0,\"data\":{\"queued\":320,\"running\":1}}");
        assertThat(reason).contains("320").contains("200");
    }

    @Test
    void evaluateBody_scriptShapeQueueDepth_stillRecognized() {
        // 计划书第 7 章剧本/mock 数据形态（字段名 queueDepth）保留兼容
        Watchman w = watchmanAt("http://unused", 200, 3, 300000);
        assertThat(w.evaluateBody("{\"queueDepth\":320}")).contains("320");
        assertThat(w.evaluateBody("{\"queueDepth\":10}")).isNull();
    }

    @Test
    void evaluateBody_missingQueueField_isAbnormal() {
        Watchman w = watchmanAt("http://unused", 200, 3, 300000);
        assertThat(w.evaluateBody("{\"code\":0,\"msg\":\"ok\"}"))
                .contains("queueDepth/queued");
    }

    // ---------- URL 拼接：尾部斜杠手误不再变成 //api/judge/status ----------

    @Test
    void statusUrl_stripsTrailingSlash() {
        assertThat(watchmanAt("http://localhost:8103/", 200, 3, 300000).statusUrl())
                .isEqualTo("http://localhost:8103/api/judge/status");
        assertThat(watchmanAt("http://localhost:8103", 200, 3, 300000).statusUrl())
                .isEqualTo("http://localhost:8103/api/judge/status");
    }

    // ---------- 端到端探针：poll() 对真实 HTTP 目标 ----------

    @Test
    void poll_consecutiveQueueBreach_createsExactlyOneIncident_respectsCooldown() throws Exception {
        String base = stubServer(200, "{\"code\":0,\"data\":{\"queued\":5,\"running\":0}}");
        // 阈值 0：queued=5 > 0 即异常；连续 2 次触发；冷却 1 小时覆盖后续所有轮询
        Watchman w = watchmanAt(base, 0, 2, 3_600_000);

        w.poll(); // 第 1 次异常：未达连续阈值
        verify(incidentService, never()).createAndRun(eq("judge-service"), argThat(s -> true));
        w.poll(); // 第 2 次异常：触发建事件
        verify(incidentService, times(1)).createAndRun(eq("judge-service"),
                argThat(s -> s != null && s.contains("watchman 自动告警")
                        && s.contains("队列深度") && s.contains("阈值 0")));
        w.poll(); // 第 3 次异常：计数重新到 2，但冷却压制
        w.poll();
        verify(incidentService, times(1)).createAndRun(eq("judge-service"), argThat(s -> true));
    }

    @Test
    void poll_healthyTarget_neverCreatesIncident() throws Exception {
        String base = stubServer(200, "{\"code\":0,\"data\":{\"queued\":0}}");
        Watchman w = watchmanAt(base, 200, 3, 300000);
        for (int i = 0; i < 6; i++) {
            w.poll();
        }
        verify(incidentService, never()).createAndRun(eq("judge-service"), argThat(s -> true));
    }

    @Test
    void poll_gateway401_countsAsAbnormalAndCreatesIncident() throws Exception {
        // 真机验证场景 A'：target 指向 mini-judge 网关而探针无 token → 永远 401。
        // 判定（见 Watchman 类注释）：401=监控通道断了，保守记异常并建事件，摘要带状态码。
        String base = stubServer(401, "{\"error\":\"Unauthorized\"}");
        Watchman w = watchmanAt(base, 200, 3, 300000);
        assertThat(w.probeOnce()).contains("401");
        w.poll();
        w.poll();
        w.poll();
        verify(incidentService, times(1)).createAndRun(eq("judge-service"),
                argThat(s -> s != null && s.contains("401")));
    }

    @Test
    void poll_unreachableTarget_createsIncidentAfterConsecutiveFails() throws Exception {
        // 真机验证场景 A：target 指向无服务的端口 → 连接拒绝 → 连续 3 次自动建事件
        Watchman w = watchmanAt("http://127.0.0.1:1", 200, 3, 300000); // 端口 1 必不可达
        w.poll();
        w.poll();
        verify(incidentService, never()).createAndRun(eq("judge-service"), argThat(s -> true));
        w.poll();
        verify(incidentService, times(1)).createAndRun(eq("judge-service"),
                argThat(s -> s != null && s.contains("探针请求失败")));
    }

    @Test
    void poll_recoveryResetsConsecutiveCounter() throws Exception {
        String base = stubServer(200, "{\"code\":0,\"data\":{\"queued\":999}}");
        Watchman w = watchmanAt(base, 200, 3, 300000);
        w.poll(); // 异常 x1
        w.poll(); // 异常 x2
        ReflectionTestUtils.setField(w, "queueThreshold", 2000); // 模拟队列回落
        w.poll(); // 正常：计数清零
        ReflectionTestUtils.setField(w, "queueThreshold", 200);
        w.poll(); // 异常 x1（若计数未清零这里就是 x3 会触发）
        verify(incidentService, never()).createAndRun(eq("judge-service"), argThat(s -> true));
    }
}
