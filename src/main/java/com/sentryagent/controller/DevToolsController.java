package com.sentryagent.controller;

import com.sentryagent.eval.ScenarioEvalRunner;
import com.sentryagent.tool.EvidenceTools;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * dev 辅助接口（计划书第 6 章 /api/chaos 家族的本地替身）：
 * POST /api/dev/seed-logs —— 向 sentry.logs-dir 写入一份"队列堆积"剧本的示例日志
 * （含 "timeout killed" 等关键行），让无 API key、无 Docker 的环境也能演示
 * fetchServiceLogs 的真实文件读取链路，同时是单测的夹具入口。
 * POST /api/dev/probe-metrics —— 直呼一次真实 getMetrics 工具（不足清单第 3 项：
 * 指标源可插拔后，mock/http/prometheus 三种 sentry.metrics.provider 的切换效果
 * 不用真跑 LLM 取证轮也能用 curl 验收，返回体带 provider 名便于核对装配）。
 *
 * <p>开关：sentry.dev-api.enabled，缺省 true（演示与单测都需要）；
 * 生产部署置 false 即可把该端点整体摘除——不依赖"内部接口没人知道"的隐身安全。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sentry.dev-api.enabled", havingValue = "true", matchIfMissing = true)
public class DevToolsController {

    private final EvidenceTools evidenceTools;
    private final ScenarioEvalRunner scenarioEvalRunner;

    /** 与 EvidenceTools 读侧同一个目录配置，写读必须一致 */
    @Value("${sentry.logs-dir:./logs}")
    private String logsDir;

    @Data
    public static class SeedReq {
        /** 要生成日志的服务名，缺省 judge-service（计划书被守护对象） */
        private String service;
    }

    @Data
    public static class ProbeMetricsReq {
        /** 要查询指标的服务名，缺省 judge-service */
        private String service;
    }

    /**
     * 剧本日志内容：与计划书第 7 章示例流程逐字对位——
     * 出现大量 "docker timeout killed"、队列深度 320 越过阈值 200 且持续爬升。
     * 这些关键行正是 MockDiagnoserGateway 首轮证据的"事实源"，
     * 演示时 LLM 关掉也能看到 fetchServiceLogs 返回真实文件内容。
     */
    private static final String SCRIPT = """
            2026-10-03 20:10:02.113 INFO  [judge-service] queue-consumer - worker pool size=6, queueDepth=12
            2026-10-03 20:11:14.502 INFO  [judge-service] queue-consumer - queueDepth=87, avg wait 3.2s
            2026-10-03 20:12:47.890 WARN  [judge-service] judge-executor - container judge-worker-3 slow: 61s for case LC-1574
            2026-10-03 20:13:59.101 ERROR [judge-service] judge-executor - docker timeout killed container judge-worker-3
            2026-10-03 20:14:12.236 WARN  [judge-service] queue-consumer - queueDepth=201 exceeded threshold 200
            2026-10-03 20:14:33.009 ERROR [judge-service] judge-executor - docker timeout killed container judge-worker-1
            2026-10-03 20:14:58.771 ERROR [judge-service] judge-executor - docker timeout killed container judge-worker-5
            2026-10-03 20:15:20.004 WARN  [judge-service] queue-consumer - queueDepth=320 and growing, oldest pending 4m12s
            2026-10-03 20:15:31.662 ERROR [judge-service] judge-executor - container judge-worker-2 restarting (attempt 2)
            2026-10-03 20:15:49.318 INFO  [judge-service] healthcheck - cpu usage 95%, memory 61%, no deploy in last 24h
            """;

    @PostMapping("/seed-logs")
    public ResponseEntity<Map<String, Object>> seedLogs(@RequestBody(required = false) SeedReq req)
            throws IOException {
        String service = (req == null || req.getService() == null || req.getService().isBlank())
                ? "judge-service" : req.getService().trim();
        // 与 EvidenceTools.sanitize 同样的清洗立场：写侧也不允许路径穿越
        String safe = service.replaceAll("[^A-Za-z0-9._-]", "_");
        Path dir = Path.of(logsDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(safe + ".log");
        Files.writeString(file, SCRIPT, StandardCharsets.UTF_8);
        long lines = SCRIPT.lines().count();
        log.info("seeded demo log {} ({} lines)", file.toAbsolutePath(), lines);
        // 回读一次真实工具的输出，证明"写完即可读"链路闭环（响应体即演示素材）
        String preview = evidenceTools.fetchServiceLogs(safe, "timeout killed");
        return ResponseEntity.ok(Map.of(
                "file", file.toAbsolutePath().toString(),
                "lines", lines,
                "fetchServiceLogsPreview", preview));
    }

    /**
     * 直呼一次真实 getMetrics 工具（与 seed-logs 的"回读工具输出"同一演示立场）。
     * @Tool 方法签名固定为 (service)——指标表达式走 sentry.metrics.default-query 配置，
     * 这里不提供覆盖口，避免为验收改工具契约；返回体带当前 provider 名，
     * 便于切换 sentry.metrics.provider 后用 curl 逐字核对装配是否生效。
     * 失败不抛 500：getMetrics 自带"查询失败"降级文本，降级也是验收素材。
     */
    @PostMapping("/probe-metrics")
    public ResponseEntity<Map<String, Object>> probeMetrics(
            @RequestBody(required = false) ProbeMetricsReq req) {
        String service = (req == null || req.getService() == null || req.getService().isBlank())
                ? "judge-service" : req.getService().trim();
        String output = evidenceTools.getMetrics(service);
        log.info("probe-metrics service={} output={}", service, output);
        return ResponseEntity.ok(Map.of(
                "service", service,
                "provider", evidenceTools.currentProvider().name(),
                "getMetricsOutput", output));
    }

    /**
     * Phase 3 评测 harness 出口（计划书第 8 章对照基线的 mock 形态）：
     * 10 条故障剧本在 mock 网关下路由确定性——跑批全绿=路由/审批/去重契约无回归；
     * 同一剧本清单接真实 LLM 后升级为诊断质量基线。GET 幂等、mock 模式秒级返回。
     */
    @GetMapping("/eval/scenarios")
    public Map<String, Object> evalScenarios() {
        var report = scenarioEvalRunner.runAll();
        log.info("scenario eval: {}", report.summary());
        return Map.of(
                "summary", report.summary(),
                "total", report.total(),
                "passed", report.passed(),
                "failed", report.failed(),
                "elapsedMs", report.elapsedMs(),
                "results", report.results());
    }
}
