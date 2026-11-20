package com.sentryagent.tool;

import com.sentryagent.metrics.MetricsProvider;
import com.sentryagent.metrics.MockMetricsProvider;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Agent 的全部可见动作 = 这 6 个显式注册的 @Tool（计划书 3.2：白名单化）。
 * 模型拿不到的能力不存在"幻觉调用"——LLM 只能在这张菜单里点菜。
 *
 * <p>分工（与计划书的框架分工一致）：
 * LangChain4j 负责把这些方法变成 function-calling 的 schema 并执行循环；
 * 方法体本身是普通 Java：读操作直连监控接口/日志文件，写操作先过一次性审批令牌。</p>
 *
 * <p>W2 真实化说明（本类三个读工具的当前形态）：</p>
 * <ul>
 *   <li>fetchServiceLogs —— 真实读本地日志文件：tail 目录 sentry.logs-dir 下的
 *       {service}.log 最后 sentry.logs-tail-lines 行，可选 keyword 过滤；
 *       文件不存在返回明确的"无日志"文案（LLM 拿到"无日志"应转向别的证据源，
 *       而不是抛异常打断取证循环）。演示/单测用 POST /api/dev/seed-logs
 *       （DevToolsController）先写入含 "timeout killed" 关键行的示例日志。</li>
 *   <li>getMetrics —— 委托可插拔指标源 {@link MetricsProvider}（不足清单第 3 项：
 *       原"硬编码 GET {target-base-url}/api/judge/status"已解绑为 mock|http|prometheus
 *       三实现 + sentry.metrics.provider 选择开关，见 com.sentryagent.metrics 包），
 *       失败降级为一行"查询失败"文本供模型推理。</li>
 *   <li>listRecentDeploys / getContainerStatus —— 保持 mock，但输出格式对齐
 *       计划书第 7 章剧本（"最近 24h 无变更"、"8 running, 2 restarting 超上限"），
 *       接 Docker 后只换方法体，schema 不变。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceTools {

    /** 日志采集目录：生产接 docker logs 后此配置作废，改为按容器名采集 */
    @Value("${sentry.logs-dir:./logs}")
    private String logsDir;

    /** 单次取证喂给模型的日志行数上限——先裁剪再喂，防 token 爆炸（计划书风险条款） */
    @Value("${sentry.logs-tail-lines:50}")
    private int logsTailLines;

    /**
     * 指标源选择开关（不足清单第 3 项）：mock|http|prometheus，默认 mock。
     * 挑用逻辑见 {@link #currentProvider()}——配置值不识别时回落 mock，默认兜底。
     */
    @Value("${sentry.metrics.provider:mock}")
    private String metricsProviderName;

    /** getMetrics 未显式指定指标表达式时传给 provider 的默认查询（prometheus 源使用） */
    @Value("${sentry.metrics.default-query:up}")
    private String metricsDefaultQuery;

    private final ApprovalRegistry approvalRegistry;
    /** Phase 3：真实 Docker 自愈执行器（开关+白名单在内部，未启用时返回 MOCK 文案） */
    private final DockerExecutor dockerExecutor;
    /**
     * 全部已装配的指标源（Spring 收集所有 MetricsProvider bean，三个实现常驻）。
     * 按 sentry.metrics.provider 从中挑用——List 注入 + 按名路由的风格与
     * NotificationListener 的 List&lt;NotificationChannel&gt; 一致；不像 llm.enabled 那样
     * 用 @ConditionalOnProperty 二选一装配，为的是"配置手误回落 mock"而非启动失败。
     */
    private final List<MetricsProvider> metricsProviders;

    /** 读：目标服务状态指标（指标源可插拔，见 com.sentryagent.metrics.MetricsProvider） */
    @Tool("查询被监控服务的运行指标：排队深度、评测中任务数、worker 忙闲。参数 service 为服务名")
    public String getMetrics(String service) {
        MetricsProvider provider = currentProvider();
        try {
            String text = provider.query(service, metricsDefaultQuery);
            return "metrics[" + service + "] = " + text;
        } catch (Exception e) {
            // 工具失败要变成"可推理的信息"而不是抛异常炸掉循环——Agent 工程的常识
            return "metrics[" + service + "] 查询失败: " + e.getMessage();
        }
    }

    /**
     * 指标源挑用（不足清单第 3 项的"选择开关"）：按 sentry.metrics.provider 配置值
     * 在已装配的 provider 里按 {@link MetricsProvider#name()} 匹配；未配置/配置值
     * 不识别时回落 {@link MockMetricsProvider}——取证链路绝不因一行配置手误起不来。
     *
     * <p>public 的原因：dev 探针端点（POST /api/dev/probe-metrics）回显当前生效的
     * provider 名，方便切换配置后用 curl 逐字核对装配。</p>
     */
    public MetricsProvider currentProvider() {
        String want = metricsProviderName == null || metricsProviderName.isBlank()
                ? MockMetricsProvider.NAME : metricsProviderName.trim().toLowerCase();
        return metricsProviders.stream()
                .filter(p -> p.name().equals(want))
                .findFirst()
                .orElseGet(this::fallbackProvider);
    }

    /** 兜底挑用：优先 mock；list 为空（测试直构造等极端形态）再退到"现造一个 mock" */
    private MetricsProvider fallbackProvider() {
        return metricsProviders.stream()
                .filter(p -> MockMetricsProvider.NAME.equals(p.name()))
                .findFirst()
                .orElseGet(() -> metricsProviders.isEmpty()
                        ? new MockMetricsProvider() : metricsProviders.get(0));
    }

    /**
     * 读：服务日志尾部。真实文件实现——读 logsDir/{service}.log 的最后 N 行，
     * keyword 非空时再做一次大小写不敏感的子串过滤。
     *
     * <p>契约要点（README 演示链与单测都依赖它）：</p>
     * <ol>
     *   <li>文件不存在/为空/过滤无命中 → 返回以 "logs[service] 无日志" 开头的明确文案，
     *       绝不抛异常；</li>
     *   <li>service 参数做安全清洗（只留字母数字点下划线短横），防路径穿越——工具参数
     *       来自 LLM 生成，属于不可信输入，清洗是白名单化思想在参数层的延伸；</li>
     *   <li>输出头部带"tail N 行/共 M 行"元信息，模型可据此判断"要不要换关键字再查".</li>
     * </ol>
     */
    @Tool("拉取服务日志文件的尾部若干行。参数 service 为服务名，keyword 为可选过滤关键字")
    public String fetchServiceLogs(@P("服务名，如 judge-service") String service,
                                   @P("可选过滤关键字，为空则返回全部尾部行") String keyword) {
        String safeService = sanitize(service);
        Path file = Path.of(logsDir, safeService + ".log");
        if (!Files.isRegularFile(file)) {
            // 明确"无日志"文案：与计划书"工具失败降级为可推理信息"同一立场
            return "logs[" + safeService + "] 无日志：文件不存在 " + file
                    + "（演示环境可先调用 POST /api/dev/seed-logs 生成示例日志）";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - Math.max(1, logsTailLines));
            List<String> tail = lines.subList(from, lines.size());
            String kw = keyword == null ? "" : keyword.trim().toLowerCase();
            List<String> matched = kw.isEmpty() ? tail : tail.stream()
                    .filter(l -> l.toLowerCase().contains(kw))
                    .toList();
            if (matched.isEmpty()) {
                return "logs[" + safeService + "] 无日志：尾部 " + tail.size()
                        + " 行中不含关键字 \"" + keyword + "\"（共 " + lines.size() + " 行）";
            }
            return "logs[" + safeService + "] tail " + tail.size() + " 行（文件 "
                    + file + "，共 " + lines.size() + " 行）:\n" + String.join("\n", matched);
        } catch (IOException e) {
            log.warn("read log file failed: {}", file, e);
            return "logs[" + safeService + "] 读取失败: " + e.getMessage();
        }
    }

    /** 读：最近部署/变更记录（保持 mock，格式对齐计划书第 7 章剧本"最近 24h 无变更"） */
    @Tool("查询服务最近的部署或配置变更记录，用于假设'是否新代码引入故障'")
    public String listRecentDeploys(String service) {
        // TODO(W3): 改读 CI/CD 平台或 git log；先保持 mock 但格式即剧本格式
        return "deploys[" + sanitize(service) + "]: 最近 24h 无变更";
    }

    /** 读：容器运行态（保持 mock，格式对齐计划书第 7 章剧本"8 个 running 超限"） */
    @Tool("列出服务相关容器的运行状态（running/exited/重启次数）")
    public String getContainerStatus(String service) {
        // TODO(W3): docker ps --filter label=sentry-managed 换成真实 inspect；
        // 当前 mock 回包格式与计划书剧本逐字对齐，便于单测与演示无缝切换
        return "containers[" + sanitize(service) + "]: 8 running, 2 restarting（超出 worker 池上限 6）";
    }

    /** 写（高危）：重启容器——必须携带审批令牌，令牌一次性；docker 启用时真实执行 */
    @Tool("重启指定容器。危险操作：必须提供审批令牌 approvalId（由值班人员批准后颁发），否则拒绝执行")
    public String restartContainer(String containerId, String approvalId, Long incidentId) {
        ApprovalRegistry.ConsumeReason reason = approvalRegistry.consumeReason(approvalId, incidentId);
        if (reason != ApprovalRegistry.ConsumeReason.OK) {
            // 不足清单第 2 项：过期与"无令牌"在文案上区分（EXPIRED 标记），供审批链路识别改判
            return refusedText(reason, incidentId);
        }
        // Phase 3（清言方案三）：TODO(W3) 兑现——真实 docker restart，三层安全：
        // HITL 令牌（上面已消费）→ sentry.docker.enabled 开关 → sentry-managed 白名单预检
        DockerExecutor.ExecResult r = dockerExecutor.restart(containerId, approvalId);
        log.info("restart container={} incident={} ok={}", containerId, incidentId, r.ok());
        return r.output();
    }

    /** 写（高危）：扩容——令牌一次性；docker 启用时真实 compose scale */
    @Tool("扩容服务 worker 数。危险操作：必须提供审批令牌 approvalId，否则拒绝执行")
    public String scaleService(String service, int workerCount, String approvalId, Long incidentId) {
        ApprovalRegistry.ConsumeReason reason = approvalRegistry.consumeReason(approvalId, incidentId);
        if (reason != ApprovalRegistry.ConsumeReason.OK) {
            return refusedText(reason, incidentId);
        }
        DockerExecutor.ExecResult r = dockerExecutor.scale(service, workerCount, approvalId);
        log.info("scale service={} workers={} incident={} ok={}",
                service, workerCount, incidentId, r.ok());
        return r.output();
    }

    /**
     * 写工具统一的 REFUSED 文案（两个写工具共用，语义必须逐字一致）：
     * 无令牌/错令牌/用过的令牌 = "令牌无效或已使用"；过期令牌 = 带 EXPIRED 标记——
     * approve 侧（IncidentService）靠这个标记把"审批窗口已关"区分出来改判 ESCALATED，
     * 而不是当作普通的"无令牌"闭环掉。文案保留"不得重试"指令（护栏单测契约）。
     */
    private static String refusedText(ApprovalRegistry.ConsumeReason reason, Long incidentId) {
        if (reason == ApprovalRegistry.ConsumeReason.EXPIRED) {
            log.warn("write action refused: approval token EXPIRED, incident={}", incidentId);
            return "REFUSED: 审批令牌已过期（EXPIRED），审批窗口已关闭，不得重试，需重新走人工审批";
        }
        return "REFUSED: 该操作未经人工审批（令牌无效或已使用），不得重试";
    }

    /**
     * 服务名清洗：只保留字母、数字、点、下划线、短横线，其余替换为下划线。
     * 工具参数由模型生成，属于不可信输入——防 "../etc/passwd" 这类路径穿越，
     * 白名单化思想在参数层的延伸。
     */
    private static String sanitize(String raw) {
        String s = raw == null ? "unknown" : raw.trim();
        if (s.isEmpty()) {
            return "unknown";
        }
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
