package com.sentryagent.controller;

import com.sentryagent.service.IncidentService;
import com.sentryagent.service.NodeProgressListener;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 事件接口（计划书第 6 章）：创建/详情/审批决策 + SSE 进度流。
 * Watchman 自动告警入口见 service.Watchman；dev 辅助接口见 DevToolsController。
 */
@Slf4j
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;

    /** SSE 轮询间隔毫秒：补发历史后每隔该间隔查一次时间线有没有新增 */
    @Value("${sentry.sse.poll-interval-ms:400}")
    private long pollIntervalMs;

    /** SSE 连接寿命毫秒：到期未终态也主动收流，防浏览器挂着死连接 */
    @Value("${sentry.sse.timeout-ms:120000}")
    private long sseTimeoutMs;

    /**
     * SSE 写线程池：每个订阅一个任务，循环"补发已发生 + 轮询新增"直到终态/超时/断连。
     * 缓存线程池对演示规模（个位数并发订阅）足够；换成图 stream 回调直连后可整个删掉。
     */
    private static final ExecutorService SSE_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sse-watcher");
        t.setDaemon(true); // 守护线程：不阻止 JVM 退出
        return t;
    });

    @Data
    public static class CreateReq {
        private String service;   // 如 judge-service
        private String summary;   // 告警摘要文本
    }

    @Data
    public static class DecisionReq {
        private boolean approve;
    }

    /** 手动发起诊断（同步跑图，mock 链路秒级——接真实 LLM 后改异步提交+本 SSE 跟进度，接口不换形态） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateReq req) {
        return ResponseEntity.ok(incidentService.createAndRun(req.getService(), req.getSummary()));
    }

    /**
     * 事件列表（计划书第 6 章契约 GET /api/incidents?status&page）。
     * 修复根因：全量测试发现契约端点缺失，GET /api/incidents 实际返回 405——
     * 补齐数据面：支持按 outcome 过滤 + 分页（page 从 0 起，size 缺省 20）。
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(incidentService.list(status, page, size));
    }

    /** 详情：终态快照 + 完整节点时间线（timeline 字段） */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
        Map<String, Object> incident = incidentService.get(id);
        return incident == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(incident);
    }

    /** 值班人员审批决策：approve=true 签发一次性令牌并续跑图 */
    @PostMapping("/{id}/decision")
    public ResponseEntity<Map<String, Object>> decision(@PathVariable long id,
                                                        @RequestBody DecisionReq req) {
        // 修复根因：对不存在的事件下决策，原先由 approve() 抛 IllegalArgumentException
        // 冒到容器变 500——"查无此事件"是入参错误，与 GET 详情对齐返回 404，
        // 且不再白白签发一枚用不上的令牌
        if (incidentService.rawSnapshot(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(incidentService.approve(id, req.isApprove()));
    }

    /**
     * SSE 进度流（计划书 3.1 Should / 第 6 章 /api/incidents/{id}/stream）：
     *
     * <p>当前实现是"补发已发生 + 轮询新增"：连上先把 NodeProgressListener 时间线里
     * 已有的 (时间,节点,摘要) 全部补发为 step_done 事件，随后每 poll-interval 毫秒
     * 拉一次时间线，只发没见过的新条目；事件出现终态（CLOSED/ESCALATED/FAILED）
     * 后发一条 done 收流；事件不存在发 error 收流；到 timeout 未终态也主动收流
     * （AWAITING_APPROVAL 停等场景下订阅者会看到"停在 awaitApproval/pending"的
     * 进度，正是要暴露给值班人的状态）。</p>
     *
     * <p>升级路线（注释钉死，接口形态不变）：LangGraph4j 的 graph.stream() 会逐节点
     * 吐 StreamOutput，把这里的"轮询监听器"换成"stream 回调直推 emitter"即可从
     * 400ms 粒度降到实时——监听器与 emitter 都只在服务/控制器边界，替换点收敛在
     * 本方法体内，图逻辑零改动。</p>
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long id) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        SSE_POOL.submit(() -> {
            int sent = 0; // 已发送的时间线下标水位线
            try {
                if (incidentService.rawSnapshot(id) == null) {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", "事件不存在: " + id)));
                    emitter.complete();
                    return;
                }
                while (true) {
                    List<NodeProgressListener.TimelineEvent> timeline =
                            incidentService.timeline(id);
                    // 补发/追新：只推水位线之后的条目，已发生的从头补齐——中途连上也不丢进度
                    for (int i = sent; i < timeline.size(); i++) {
                        NodeProgressListener.TimelineEvent e = timeline.get(i);
                        emitter.send(SseEmitter.event().name("step_done").data(Map.of(
                                "at", e.at(), "node", e.node(), "digest", e.digest())));
                        sent = i + 1;
                    }
                    Map<String, Object> snap = incidentService.rawSnapshot(id);
                    String outcome = snap == null ? "" : String.valueOf(
                            snap.getOrDefault(com.sentryagent.graph.IncidentState.OUTCOME, ""));
                    boolean terminal = outcome.equals("CLOSED")
                            || outcome.equals("ESCALATED") || outcome.equals("FAILED");
                    if (terminal) {
                        emitter.send(SseEmitter.event().name("done")
                                .data(Map.of("outcome", outcome)));
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(pollIntervalMs);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (Exception e) {
                // 客户端断连（IOException）最常见：静默收流即可，不是服务端故障
                log.debug("sse stream closed: id={} cause={}", id, e.toString());
                emitter.complete();
            }
        });
        return emitter;
    }
}
