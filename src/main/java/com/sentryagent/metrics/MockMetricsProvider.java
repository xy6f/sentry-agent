package com.sentryagent.metrics;

import org.springframework.stereotype.Component;

/**
 * 剧本化指标源（sentry.metrics.provider=mock，默认值）：零网络、零依赖，
 * 输出与 MockDiagnoserGateway 补充证据的 metrics 段同口径（cpu=95%、queueDepth=320）——
 * 延续"mock 输出的格式契约变了，解析器就该跟着变，单测立刻红"的一贯纪律。
 *
 * <p>定位：演示/单测的默认兜底。无 Prometheus、无被守护系统时取证链路照样能走通；
 * EvidenceTools 遇到不识别的 provider 配置值也回落到本实现（见 currentProvider）。</p>
 */
@Component
public class MockMetricsProvider implements MetricsProvider {

    /** 配置开关取值（EvidenceTools 按名挑用与回落兜底都用它） */
    public static final String NAME = "mock";

    /** 剧本文案：与 MockDiagnoserGateway.SECOND_EVIDENCE 的 metrics 段同口径 */
    static final String SCRIPT_TEXT =
            "cpu=95%, queueDepth=320（> 阈值 200），评测 worker 池饱和迹象";

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 返回剧本文案。mock 永不失败也永不触网——service/metric 即使为 null 也照常返回
     * （"正常/降级"在本实现里的差别只是文案稳定性，这正是兜底实现的本分）。
     */
    @Override
    public String query(String service, String metric) {
        return SCRIPT_TEXT + "（mock 剧本指标，真实源请配 sentry.metrics.provider=http|prometheus）";
    }
}
