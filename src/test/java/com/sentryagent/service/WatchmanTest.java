package com.sentryagent.service;

import com.sentryagent.watchman.RuleEvaluator;
import com.sentryagent.watchman.Watchman;
import com.sentryagent.watchman.WatchmanRule;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Watchman 回归测试（Phase 2 多规则化改造后：类迁至 watchman 包，行为语义全部钉死）。
 *
 * <p>两部分：
 * 1. 判定逻辑回归——原 WatchmanTest 的 evaluateBody/statusUrl 用例迁移到
 *    RuleEvaluator（新判定归属地），queueDepth/queued 双字段、401 判异常、尾部斜杠
 *    修复的契约一条不少；
 * 2. 调度语义回归——poll() 的连续阈值/冷却/恢复清零用默认规则（未配 rules 时的
 *    兼容形态）跑，保证"未配置 rules 时行为与旧版完全一致"。</p>
 *
 * <p>HTTP 目标用 JDK com.sun.net.httpserver 起随机端口，零新增依赖。</p>
 */
class WatchmanTest {

    private IncidentService incidentService;
    private HttpServer server;
    private RuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        incidentService = mock(IncidentService.class);
        evaluator = new RuleEvaluator();
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

    /** 默认规则形态的 Watchman（未配 rules 时的兼容形态，语义=旧版单目标） */
    private Watchman watchmanAt(String baseUrl, int queueThreshold,
                                int consecutiveTimes, long cooldownMs) {
        Watchman w = new Watchman(incidentService, evaluator);
        ReflectionTestUtils.setField(w, "targetBaseUrl", baseUrl);
        ReflectionTestUtils.setField(w, "queueThreshold", queueThreshold);
        ReflectionTestUtils.setField(w, "consecutiveTimes", consecutiveTimes);
        ReflectionTestUtils.setField(w, "cooldownMs", cooldownMs);
        return w;
    }

    /** 构造 http-probe 规则的评估输入（RuleEvaluator 直测用） */
    private WatchmanRule probeRule(String url) {
        return new WatchmanRule("judge-service", WatchmanRule.TYPE_HTTP_PROBE,
                url, null, null, 200, List.of());
    }

    // ---------- 判定逻辑回归（原 evaluateBody 用例 → RuleEvaluator） ----------

    @Test
    void evaluateBody_realMiniJudgeFieldQueued_belowThreshold_isNormal() {
        // mini-judge 真实 /api/judge/status 回包形态（真机验证抓到的实录）
        assertThat(evaluator.judgeQueueDepth("{\"code\":0,\"msg\":\"ok\",\"data\":"
                + "{\"queued\":0,\"running\":0,\"workerBusy\":0,\"workerCapacity\":4}}")).isNull();
    }

    @Test
    void evaluateBody_realMiniJudgeFieldQueued_aboveThreshold_isAbnormal() {
        String reason = evaluator.judgeQueueDepth("{\"code\":0,\"data\":{\"queued\":320,\"running\":1}}");
        assertThat(reason).contains("320").contains("阈值 200");
    }

    @Test
    void evaluateBody_scriptShapeQueueDepth_stillRecognized() {
        // 计划书剧本形态 queueDepth 优先兼容
        assertThat(evaluator.judgeQueueDepth("{\"queueDepth\":320}")).contains("320");
        assertThat(evaluator.judgeQueueDepth("{\"queueDepth\":10}")).isNull();
    }

    @Test
    void httpProbe_missingQueueField_isNormal2xx() {
        // 语义修正说明：旧 evaluateBody 把"无深度字段"判异常——那是队列型端点的保守语义；
        // 多规则化后 http-probe 是通用探活（2xx=健康），深度判定只在 body 里真的有深度字段时生效。
        String base = stubServerQuiet(200, "{\"code\":0,\"msg\":\"ok\"}");
        assertThat(evaluator.evaluate(probeRule(base + "/api/judge/status"))).isNull();
    }

    @Test
    void statusUrl_stripsTrailingSlash() {
        // 契约迁至 Watchman.stripTrailingSlash（默认规则拼 URL 时调用）
        assertThat(Watchman.stripTrailingSlash("http://localhost:8103/"))
                .isEqualTo("http://localhost:8103");
        assertThat(Watchman.stripTrailingSlash("http://localhost:8103"))
                .isEqualTo("http://localhost:8103");
        Watchman w = watchmanAt("http://localhost:8103/", 200, 3, 300000);
        // 默认规则 URL 经拼装后不含 //api
        WatchmanRule r = w.effectiveRules().get(0);
        assertThat(r.url()).isEqualTo("http://localhost:8103/api/judge/status");
    }

    // ---------- 端到端探针：poll() 对真实 HTTP 目标（默认规则=旧行为兼容） ----------

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
        // 真机验证场景 A'：target 指向网关而探针无 token → 永远 401 = 监控失明必须出声
        String base = stubServer(401, "{\"error\":\"Unauthorized\"}");
        Watchman w = watchmanAt(base, 200, 3, 300000);
        assertThat(evaluator.evaluate(probeRule(base + "/api/judge/status"))).contains("401");
        w.poll();
        w.poll();
        w.poll();
        verify(incidentService, times(1)).createAndRun(eq("judge-service"),
                argThat(s -> s != null && s.contains("401")));
    }

    @Test
    void poll_unreachableTarget_createsIncidentAfterConsecutiveFails() throws Exception {
        // 真机验证场景 A：target 指向无服务端口 → 连接拒绝 → 连续 3 次自动建事件
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

    // ---------- Phase 2 新能力：多服务多规则 ----------

    @Test
    void multiRules_eachServicePolledIndependently() throws Exception {
        // 两条规则：一条 2xx 健康、一条探不可达端口——后者独立触发建事件，前者永不建
        String base = stubServer(200, "{\"code\":0,\"data\":{\"queued\":0}}");
        Watchman w = new Watchman(incidentService, evaluator);
        ReflectionTestUtils.setField(w, "targetBaseUrl", base);
        ReflectionTestUtils.setField(w, "consecutiveTimes", 2);
        ReflectionTestUtils.setField(w, "cooldownMs", 3_600_000);
        ReflectionTestUtils.setField(w, "rules", List.of(
                new WatchmanRule("healthy-svc", WatchmanRule.TYPE_HTTP_PROBE,
                        base + "/api/judge/status", null, null, 200, List.of()),
                new WatchmanRule("dead-svc", WatchmanRule.TYPE_HTTP_PROBE,
                        "http://127.0.0.1:1/health", null, null, 200, List.of())));

        for (int i = 0; i < 3; i++) {
            w.poll();
        }
        verify(incidentService, never()).createAndRun(eq("healthy-svc"), argThat(s -> true));
        verify(incidentService, times(1)).createAndRun(eq("dead-svc"),
                argThat(s -> s != null && s.contains("watchman 自动告警 [http-probe]")));
    }

    @Test
    void prometheusRule_valueAboveThreshold_abnormal() {
        // Prometheus 规则：用正则形态的假响应（真实 /api/v1/query 的 value:[ts,"num"] 形态）
        RuleEvaluator stub = new RuleEvaluator(url -> {
            if (url.contains("/api/v1/query")) {
                return "{\"status\":\"success\",\"data\":{\"result\":"
                        + "[{\"metric\":{},\"value\":[1759000000,\"0.93\"]}]}}";
            }
            throw new IllegalStateException("unexpected url " + url);
        });
        WatchmanRule r = new WatchmanRule("seckill-api", WatchmanRule.TYPE_PROMETHEUS,
                null, "heap_used/heap_max", "http://prom:9090", 0.85, List.of());
        String reason = stub.evaluate(r);
        assertThat(reason).contains("0.93").contains("阈值 0.85");

        // 低于阈值 → 正常
        RuleEvaluator stubLow = new RuleEvaluator(url ->
                "{\"status\":\"success\",\"data\":{\"result\":"
                        + "[{\"metric\":{},\"value\":[1759000000,\"0.40\"]}]}}");
        assertThat(stubLow.evaluate(r)).isNull();
    }

    @Test
    void prometheusRule_emptyResult_isAbnormal() {
        // 无样本 = 监控失明，保守异常（目标未暴露指标）
        RuleEvaluator stub = new RuleEvaluator(url ->
                "{\"status\":\"success\",\"data\":{\"result\":[]}}");
        WatchmanRule r = new WatchmanRule("app", WatchmanRule.TYPE_PROMETHEUS,
                null, "q", "http://prom:9090", 0.85, List.of());
        assertThat(stub.evaluate(r)).contains("无样本");
    }

    @Test
    void invalidRuleType_isAbnormalNotException() {
        // 配置手误早失败原则：未知类型返回异常文本而非抛异常（巡检线程不能死）
        RuleEvaluator stub = new RuleEvaluator(url -> "");
        WatchmanRule r = new WatchmanRule("app", "mqtt-probe", null, null, null, 0, List.of());
        assertThat(stub.evaluate(r)).contains("规则配置无效");
    }

    // ---------- 辅助 ----------


    private String stubServerQuiet(int status, String body) {
        try {
            return stubServer(status, body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
