package com.sentryagent.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP 状态端点指标源（sentry.metrics.provider=http）：
 * EvidenceTools.getMetrics 原硬编码行为的配置化——原来写死
 * GET {target-base-url}/api/judge/status（mini-judge 专属），现在端点地址改为
 * sentry.metrics.http-url 可配，指向任意暴露状态端点的被守护系统
 * （Spring Boot 用 actuator/health，Go/Node 自己写一个 /health，
 * 与 Watchman http-probe 规则"项目只需暴露一个端点"同一立场）。
 *
 * <p>缺省等价迁移：未配 http-url 时回落旧行为
 * {sentry.target-base-url}/api/judge/status——既有部署不改任何配置，行为逐字不变。</p>
 */
@Slf4j
@Component
public class HttpEndpointMetricsProvider implements MetricsProvider {

    /** 配置开关取值（EvidenceTools 按名挑用） */
    public static final String NAME = "http";

    /** 状态端点地址（构造期解析定稿） */
    private final String url;

    private final RestClient rest;

    /**
     * Spring 装配：http-url 未配时回落旧行为 {target-base-url}/api/judge/status。
     * @Autowired 显式指定 autowire 候选——类里还有 public 的测试装配构造，
     * 双构造且无默认构造时 Spring 会拒绝推断，不标注直接"no default constructor"启动失败。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public HttpEndpointMetricsProvider(
            @Value("${sentry.metrics.http-url:}") String httpUrl,
            @Value("${sentry.target-base-url:http://localhost:8080}") String targetBaseUrl) {
        this(resolveUrl(httpUrl, targetBaseUrl), RestClient.builder());
    }

    /**
     * 测试装配口（public：EvidenceTools 的单测在 tool 包里也要用它手工组 List）：
     * 传入预配置 builder，MockRestServiceServer.bindTo(builder) 即可拦截请求。
     */
    public HttpEndpointMetricsProvider(String url, RestClient.Builder builder) {
        this.url = url;
        this.rest = builder.build();
    }

    /**
     * 端点解析：显式配置优先；未配 = 旧行为 {target-base-url}/api/judge/status
     * （base 尾部斜杠容错，与 Watchman/RuleEvaluator 同款处理）。
     */
    static String resolveUrl(String httpUrl, String targetBaseUrl) {
        if (httpUrl != null && !httpUrl.isBlank()) {
            return httpUrl.trim();
        }
        String base = targetBaseUrl == null ? "" : targetBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/api/judge/status";
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * GET 状态端点并原样返回响应体（JSON 明文，EvidenceTools 统一加 metrics[service] 前缀）。
     * metric 参数在本实现无意义（端点即全量状态），保留只为对齐接口契约。
     *
     * <p>失败语义：RestClient 对 4xx/5xx 与连接失败默认抛异常，
     * 由 EvidenceTools 统一降级为 "metrics[service] 查询失败: ..." 文本。</p>
     */
    @Override
    public String query(String service, String metric) {
        // URI.create 直传预组好的 URL，避免配置里的 {} 被当 URI 模板占位符解析
        String body = rest.get().uri(java.net.URI.create(url)).retrieve().body(String.class);
        return body == null ? "" : body;
    }
}
