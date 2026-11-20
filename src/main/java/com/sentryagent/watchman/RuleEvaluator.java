package com.sentryagent.watchman;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则执行器：把一条 {@link WatchmanRule} 变成一次"探测 → 异常原因/null"的判定。
 * 纯逻辑（网络调用经注入的 probe 函数），全部可单测——这是从 Watchman 拆出来的原因：
 * 原来的 probeOnce 把"怎么探"和"探完怎么判"耦在一个类里，多规则化后必须分离。
 *
 * <p>网络访问通过 {@link ProbeFn} 函数接口注入（生产实现用 RestClient，测试用 stub），
 * 本类不直接持有 RestClient——同项目一贯的"节点函数纯 Java、LLM/IO 在边缘"分层纪律。</p>
 */
@Slf4j
@Component
public class RuleEvaluator {

    /** 网络探测函数：url -> 响应体（2xx）；非 2xx/IO 失败抛异常（由各类型分支翻译成异常原因） */
    @FunctionalInterface
    public interface ProbeFn {
        String get(String url) throws Exception;
    }

    /** 兜底单值查询：prometheus instant query 的响应体（测试注入用） */
    @FunctionalInterface
    public interface QueryFn {
        String query(String url) throws Exception;
    }

    private static final Pattern QUEUE_DEPTH = Pattern.compile("\"queueDepth\"\\s*:\\s*(\\d{1,9})");
    private static final Pattern QUEUED = Pattern.compile("\"queued\"\\s*:\\s*(\\d{1,9})");
    /**
     * prometheus /api/v1/query 响应里的数值："value":[<ts>,"<num>"] 形态。
     * public 的原因（不足清单第 3 项）：诊断取证的 PrometheusMetricsProvider 与本类
     * 共用这把解析尺子——巡检与取证的数值口径不分家，正则改动两处单测同时红。
     */
    public static final Pattern PROM_VALUE = Pattern.compile("\"value\"\\s*:\\s*\\[[^\\]]*,\\s*\"(-?\\d+(?:\\.\\d+)?)\"");

    private final ProbeFn probeFn;

    /** 日志目录（与 EvidenceTools 同源配置 sentry.logs-dir，默认 ./logs） */
    @org.springframework.beans.factory.annotation.Value("${sentry.logs-dir:./logs}")
    private String logsDir;

    public RuleEvaluator() {
        this(RuleEvaluator::defaultProbe);
    }

    public RuleEvaluator(ProbeFn probeFn) {
        this.probeFn = probeFn;
    }

    String logsDir() {
        return logsDir == null || logsDir.isBlank() ? "./logs" : logsDir;
    }

    /** 生产实现：RestClient GET（2xx 返回 body，否则抛——异常翻译在各类型分支） */
    static String defaultProbe(String url) throws Exception {
        return RestClient.create().get().uri(url).retrieve().body(String.class);
    }

    /**
     * 执行一条规则：返回 null=正常，非 null=异常原因文本（进 ALERT 通道）。
     * 未知类型视为配置错误，保守返回异常（告警系统"失明必须出声"原则）。
     */
    public String evaluate(WatchmanRule rule) {
        if (rule == null || rule.service() == null || !rule.knownType()) {
            return "规则配置无效（service/type 必填且 type 须为 http-probe|prometheus|log-keyword）";
        }
        return switch (rule.type()) {
            case WatchmanRule.TYPE_HTTP_PROBE -> evalHttpProbe(rule);
            case WatchmanRule.TYPE_PROMETHEUS -> evalPrometheus(rule);
            case WatchmanRule.TYPE_LOG_KEYWORD -> evalLogKeyword(rule);
            default -> "未知规则类型 " + rule.type();
        };
    }

    /** http-probe：GET url，2xx 即正常；4xx/5xx/IO 异常都记异常并带状态码/原因 */
    private String evalHttpProbe(WatchmanRule rule) {
        if (rule.url() == null || rule.url().isBlank()) {
            return "http-probe 规则缺少 url 配置";
        }
        String body;
        try {
            body = probeFn.get(rule.url());
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return "探针收到 HTTP " + e.getStatusCode()
                    + (e.getStatusCode().value() == 401
                       ? "：监控通道鉴权失败（服务可能健在，探针读不到）"
                       : "：被监控端点返回错误状态");
        } catch (Exception e) {
            return "探针请求失败: " + e.getMessage();
        }
        // 兼容能力保留：健康端点 body 里若带队列深度字段，按规则阈值判定（旧单目标行为）
        String depthVerdict = judgeQueueDepth(body, rule.threshold());
        return depthVerdict;
    }

    /** prometheus：instant query，结果值 > threshold 记异常 */
    private String evalPrometheus(WatchmanRule rule) {
        if (rule.query() == null || rule.query().isBlank()) {
            return "prometheus 规则缺少 query 配置";
        }
        String prom = rule.promUrl() == null || rule.promUrl().isBlank()
                ? "http://localhost:9090" : rule.promUrl();
        String body;
        try {
            String url = stripTrailingSlash(prom)
                    + "/api/v1/query?query=" + java.net.URLEncoder.encode(rule.query(),
                       java.nio.charset.StandardCharsets.UTF_8);
            body = probeFn.get(url);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return "Prometheus 查询收到 HTTP " + e.getStatusCode();
        } catch (Exception e) {
            return "Prometheus 查询失败: " + e.getMessage();
        }
        Matcher m = PROM_VALUE.matcher(body == null ? "" : body);
        if (!m.find()) {
            // 空结果（无样本）本身即异常：目标没暴露指标 = 监控失明
            return "Prometheus 查询无样本（目标未暴露该指标？）query=" + rule.query();
        }
        double value = Double.parseDouble(m.group(1));
        return value > rule.threshold()
                ? "指标 " + rule.query() + " = " + value + "（阈值 " + rule.threshold() + "）"
                : null;
    }

    /** log-keyword：扫描 watchman 日志目录里 {service}.log，任一关键字命中即异常 */
    private String evalLogKeyword(WatchmanRule rule) {
        if (rule.keywords() == null || rule.keywords().isEmpty()) {
            return "log-keyword 规则缺少 keywords 配置";
        }
        java.nio.file.Path logFile = java.nio.file.Path.of(logsDir(), rule.service() + ".log");
        if (!java.nio.file.Files.exists(logFile)) {
            return null;   // 无日志文件不算异常（服务可能还没产生日志）——与 fetchServiceLogs 语义一致
        }
        try {
            // 只扫尾部 200 行：巡检高频运行，全文件扫会随时间 O(n) 恶化
            List<String> tail = tailLines(logFile, 200);
            for (String line : tail) {
                for (String kw : rule.keywords()) {
                    if (line.contains(kw)) {
                        return "日志命中关键字 [" + kw + "]: " + line.trim();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return "日志扫描失败: " + e.getMessage();
        }
    }

    /** 旧单目标行为兼容：body 含队列深度字段时按阈值判定（评估器复用点；测试直测用旧默认 200） */
    public String judgeQueueDepth(String body) {
        return judgeQueueDepth(body, 200);
    }

    /** 带阈值的深度判定（evalHttpProbe 主路径：threshold 来自规则配置，测试可反射设 0/2000） */
    public String judgeQueueDepth(String body, double threshold) {
        String text = body == null ? "" : body;
        Matcher m = QUEUE_DEPTH.matcher(text);
        if (m.find()) {
            return judgeDepth(m.group(1), threshold);
        }
        m = QUEUED.matcher(text);
        if (m.find()) {
            return judgeDepth(m.group(1), threshold);
        }
        return null;   // 无深度字段 = 该端点不是队列型，http-probe 语义下 2xx 即正常
    }

    private String judgeDepth(String digits, double threshold) {
        double depth = Double.parseDouble(digits);
        return depth > threshold
                ? "队列深度 queued/queueDepth=" + (int) depth + "（阈值 " + (int) threshold + "）"
                : null;
    }

    private String judgeDepth(String digits) {
        return judgeDepth(digits, 200);   // 旧默认阈值 200，保持向后兼容
    }

    static String stripTrailingSlash(String s) {
        String base = s == null ? "" : s.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** 读文件尾部 N 行（Watchman 与 fetchServiceLogs 语义一致的轻量实现） */
    static List<String> tailLines(java.nio.file.Path file, int n) throws Exception {
        List<String> all = java.nio.file.Files.readAllLines(file);
        return all.size() <= n ? all : all.subList(all.size() - n, all.size());
    }
}
