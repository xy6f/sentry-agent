package com.sentryagent.metrics;

import com.sentryagent.watchman.RuleEvaluator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;

/**
 * Prometheus 指标源（sentry.metrics.provider=prometheus）：向 prom-url 发 instant query，
 * 解析结果数值返回明文。被守护项目只需暴露 /metrics（Prometheus 侧配好 scrape）即可接入，
 * 与 Watchman 的 prometheus 规则（RuleEvaluator.evalPrometheus）同一套接入立场——
 * "接监控 = 项目一行不改地暴露指标 + 平台侧加一段配置"。
 *
 * <p>数值正则直接复用 {@link RuleEvaluator#PROM_VALUE}（"value":[&lt;ts&gt;,"&lt;num&gt;"] 形态）：
 * 巡检与取证共用同一把解析尺子，解析口径变了，两处单测会同时红，不会出现
 * "巡检判正常、取证读出另一个数"的分叉。</p>
 *
 * <p>空结果（无样本）不抛异常，返回明确文案——"目标没暴露该指标"本身就是可推理的
 * 证据（监控失明必须出声），对齐 RuleEvaluator 的空结果语义。</p>
 */
@Component
public class PrometheusMetricsProvider implements MetricsProvider {

    /** 配置开关取值（EvidenceTools 按名挑用） */
    public static final String NAME = "prometheus";

    /** prometheus 服务地址（缺省 http://localhost:9090，与 WatchmanRule.prom-url 同口径） */
    private final String promUrl;

    private final RestClient rest;

    /**
     * Spring 装配：prom-url 缺省 9090。@Autowired 显式指定 autowire 候选——
     * 类里还有 public 的测试装配构造，双构造且无默认构造时 Spring 会拒绝推断，
     * 不标注直接"no default constructor"启动失败。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PrometheusMetricsProvider(
            @Value("${sentry.metrics.prom-url:http://localhost:9090}") String promUrl) {
        this(promUrl, RestClient.builder());
    }

    /**
     * 测试装配口（public：EvidenceTools 的单测在 tool 包里也要用它手工组 List）：
     * 传入预配置 builder，MockRestServiceServer.bindTo(builder) 即可拦截请求。
     */
    public PrometheusMetricsProvider(String promUrl, RestClient.Builder builder) {
        this.promUrl = promUrl;
        this.rest = builder.build();
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * instant query：GET {prom-url}/api/v1/query?query={metric}，命中样本返回
     * "指标 &lt;expr&gt; = &lt;value&gt;" 明文。metric 为空时回落 "up"
     * （Prometheus 惯例的存活探针表达式）。
     *
     * <p>失败语义：HTTP 非 2xx / 连接失败抛异常，由 EvidenceTools 统一降级为
     * "查询失败"文本；抛出的消息刻意不带"查询失败"字样，避免与外层前缀重复。</p>
     */
    @Override
    public String query(String service, String metric) {
        String expr = metric == null || metric.isBlank() ? "up" : metric.trim();
        String url = stripTrailingSlash(promUrl) + "/api/v1/query?query="
                + URLEncoder.encode(expr, StandardCharsets.UTF_8);
        String body;
        try {
            // 用 URI.create 而非 uri(String)：PromQL 表达式里的 {} 会被 URI 模板
            // 误认成占位符（"Map has no value for 'job'"），预编码后的完整 URI 直传无此坑
            body = rest.get().uri(java.net.URI.create(url)).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("Prometheus 端点返回 HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
        Matcher m = RuleEvaluator.PROM_VALUE.matcher(body == null ? "" : body);
        if (!m.find()) {
            // 空结果（无样本）：目标没暴露该指标——返回可推理文案而非抛异常（对齐 RuleEvaluator）
            return "无样本（目标未暴露该指标？query=" + expr + "）";
        }
        return "指标 " + expr + " = " + m.group(1) + "（prometheus instant query）";
    }

    /** 尾部斜杠容错（Watchman/RuleEvaluator 同款处理） */
    private static String stripTrailingSlash(String s) {
        String base = s == null ? "" : s.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }
}
