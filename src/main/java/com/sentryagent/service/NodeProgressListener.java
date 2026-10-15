package com.sentryagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 节点进度时间线登记处：图每完成一个节点，就追加一条 (时间, 节点, outcome 摘要)。
 *
 * <p>接线方式：IncidentGraphFactory 用 {@code timed(name, impl)} 包装每个节点动作，
 * 节点返回后立刻回调这里——不依赖 LLM 是否真实存在，mock 链路同样有完整时间线
 * （可观测性不能被模型绑架，这是本项目的一条原则）。</p>
 *
 * <p>消费方有两个：IncidentService 的详情快照（timeline 字段）与 SSE 端点
 * （轮询这里的新条目补发给前端）。W 后续升级路线：改为 LangGraph4j 的
 * stream/streamSnapshots 回调直推 SSE，把"轮询新增"换成"事件直连"，
 * 接口形态不变——见 IncidentController.stream 注释。</p>
 */
@Slf4j
@Component
public class NodeProgressListener {

    /** 一条时间线事件：节点完成时刻(epoch millis)、节点名、产出摘要(截断防刷屏) */
    public record TimelineEvent(long at, String node, String digest) {
    }

    /** 摘要最大长度：时间线给人看进度，不给人看全文 */
    private static final int DIGEST_MAX = 160;

    private final Map<Long, List<TimelineEvent>> timelines = new ConcurrentHashMap<>();

    /** 图节点完成后回调。incidentId 非法（-1，测试里手搓状态）时静默忽略 */
    public void onNodeComplete(long incidentId, String node, Map<String, Object> output) {
        if (incidentId < 0) {
            return;
        }
        String digest = summarize(output);
        timelines.computeIfAbsent(incidentId, k -> new CopyOnWriteArrayList<>())
                .add(new TimelineEvent(System.currentTimeMillis(), node, digest));
        log.debug("timeline incident={} node={} digest={}", incidentId, node, digest);
    }

    /** 只读快照：某事件当前时间线（SSE 补发与 GET 详情共用） */
    public List<TimelineEvent> timeline(long incidentId) {
        return Collections.unmodifiableList(
                new ArrayList<>(timelines.getOrDefault(incidentId, List.of())));
    }

    /** 摘要化节点输出：取每个 value 的 toString 前 40 字符，整体截断 */
    private static String summarize(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return "skipped(复用已有产出)";
        }
        StringBuilder sb = new StringBuilder();
        output.forEach((k, v) -> {
            String s = String.valueOf(v);
            if (s.length() > 40) {
                s = s.substring(0, 40) + "...";
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(k).append('=').append(s);
        });
        String all = sb.toString();
        return all.length() > DIGEST_MAX ? all.substring(0, DIGEST_MAX) + "..." : all;
    }
}
