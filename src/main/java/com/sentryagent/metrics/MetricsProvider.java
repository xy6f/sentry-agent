package com.sentryagent.metrics;

/**
 * 诊断取证阶段的指标源抽象（不足清单第 3 项：getMetrics 解绑 mini-judge）。
 *
 * <p>背景：原来 EvidenceTools.getMetrics 硬编码 GET {target-base-url}/api/judge/status，
 * 指标源与被守护系统 mini-judge 死绑——换一个被守护项目就得改核心代码。
 * 本接口把"去哪查、怎么解析成明文"收进实现类，getMetrics 只负责
 * "委托当前 provider + 失败降级"；换指标源 = 改一行配置，@Tool 契约不动。</p>
 *
 * <p>三个实现（选择开关 sentry.metrics.provider，默认 mock，装配与挑用方式见
 * EvidenceTools.currentProvider，路由风格与 NotificationListener 的
 * List&lt;NotificationChannel&gt; 一致）：</p>
 * <ul>
 *   <li>{@link MockMetricsProvider}（mock）：剧本文案，零网络，默认兜底；</li>
 *   <li>{@link HttpEndpointMetricsProvider}（http）：GET 状态端点，原硬编码行为的配置化；</li>
 *   <li>{@link PrometheusMetricsProvider}（prometheus）：instant query + 数值解析，
 *       正则复用 RuleEvaluator.PROM_VALUE，与 Watchman 巡检同口径。</li>
 * </ul>
 */
public interface MetricsProvider {

    /** 供应者标识（与 sentry.metrics.provider 的取值一一对应：mock|http|prometheus） */
    String name();

    /**
     * 查询指标，返回人类可读文本——与 @Tool 的输出契约一致（直接喂给 LLM 推理的明文）。
     *
     * <p>失败语义：实现类查询失败直接抛异常（连接失败/HTTP 非 2xx），
     * 由 EvidenceTools.getMetrics 统一翻译成 "metrics[service] 查询失败: ..." 降级文本——
     * 一次指标查询失败绝不能炸掉取证循环（工具失败 = 可推理的信息，Agent 工程常识）。</p>
     *
     * @param service 被监控服务名（实现类可回显/路由；mock 忽略）
     * @param metric  指标表达式（prometheus 型为 instant query 表达式；http 型忽略。
     *                EvidenceTools 从 sentry.metrics.default-query 配置取值传入）
     * @return 人类可读的指标文本（非 null）
     */
    String query(String service, String metric);
}
