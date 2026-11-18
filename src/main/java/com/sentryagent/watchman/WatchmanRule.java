package com.sentryagent.watchman;

import java.util.List;

/**
 * 一条巡检规则（清言方案三·改造一："监控任何项目 = 加一段 YAML"）。
 *
 * <p>三种类型（type 判别）：</p>
 * <ul>
 *   <li><b>http-probe</b>：定时 GET url，非 2xx 记异常——被监控项目只需暴露一个健康端点，
 *       Spring Boot 用 actuator，Go/Node 自己写一个 /health；</li>
 *   <li><b>prometheus</b>：向 Prometheus 发 instant query，结果值 &gt; threshold 记异常——
 *       项目暴露 /metrics 即可接入（Prometheus 侧配好 scrape）；</li>
 *   <li><b>log-keyword</b>：在 watchman 的日志目录里按关键字扫描 {service}.log——
 *       适合"项目一行不改、只看它日志里有没有 OutOfMemoryError/FATAL"的兜底场景。</li>
 * </ul>
 *
 * <p>YAML 形态（sentry.watchman.rules 列表，Spring 绑定为 List&lt;WatchmanRule&gt;）：</p>
 * <pre>
 * sentry:
 *   watchman:
 *     rules:
 *       - service: seckill-api
 *         type: http-probe
 *         url: http://localhost:8080/actuator/health
 *       - service: seckill-api
 *         type: prometheus
 *         query: jvm_memory_used_bytes{area='heap'}/jvm_memory_max_bytes{area='heap'}
 *         threshold: 0.85
 *         prom-url: http://localhost:9090
 *       - service: legacy-app
 *         type: log-keyword
 *         keywords: [OutOfMemoryError, FATAL]
 * </pre>
 *
 * <p>未配置 rules 时回落到内置默认规则（judge-service 队列深度，兼容旧行为与既有单测）。</p>
 *
 * @param service    被守护服务名（进 IncidentState.SERVICE）
 * @param type       http-probe | prometheus | log-keyword
 * @param url        http-probe 的目标 URL
 * @param query      prometheus 的 instant query 表达式
 * @param promUrl    prometheus 服务地址（缺省 http://localhost:9090）
 * @param threshold  prometheus 的告警阈值（query 结果 > threshold 触发）；http-probe 忽略
 * @param keywords   log-keyword 的关键字列表（任一命中即异常）
 */
public record WatchmanRule(String service, String type, String url, String query,
                           String promUrl, double threshold, List<String> keywords) {

    public static final String TYPE_HTTP_PROBE = "http-probe";
    public static final String TYPE_PROMETHEUS = "prometheus";
    public static final String TYPE_LOG_KEYWORD = "log-keyword";

    /** 类型合法性（配置手误早失败） */
    public boolean knownType() {
        return TYPE_HTTP_PROBE.equals(type) || TYPE_PROMETHEUS.equals(type)
                || TYPE_LOG_KEYWORD.equals(type);
    }

    /** 规则唯一标识（日志/冷却键 = service + type，同服务可挂多条不同型规则） */
    public String key() {
        return service + "#" + type;
    }
}
