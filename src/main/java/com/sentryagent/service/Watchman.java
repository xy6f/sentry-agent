package com.sentryagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watchman 自动巡检（计划书 3.1 Must"告警入口"的自动半边 + 第 7 章示例流程第一行）：
 * 定时轮询被守护系统 {sentry.target-base-url}/api/judge/status，
 * 满足以下任一条件记一次"异常"：
 * <ul>
 *   <li>HTTP 请求失败（服务不可达本身即故障信号；4xx/5xx 响应也归此类，见下方"401 判定"）；</li>
 *   <li>响应 JSON 里队列深度超过 sentry.watchman.queue-threshold；</li>
 *   <li>响应 200 但没有可解析的队列深度字段（接口形态不符，保守记异常）。</li>
 * </ul>
 * 同一服务连续异常达到 sentry.watchman.consecutive-times 次，自动创建 Incident
 * 并启动诊断图（对应计划书第 7 章"连续 5 次 &gt;200 触发"）。
 *
 * <p>【真机契约修正（watchman 真链路验证发现）】计划书与早期 mock 数据里队列字段写作
 * queueDepth，但 mini-judge 真实接口 GET /api/judge/status 返回的是
 * {"code":0,"data":{"queued":N,"running":N,...}}——字段名是 queued。
 * 原实现只匹配 "queueDepth"，导致对着健在的 judge-service 每轮都判"响应未含
 * queueDepth 字段"异常，连续 3 次后给健康服务自动建故障事件（误报）。
 * 现在两个字段都认：queueDepth 优先（兼容计划书剧本形态），缺失时回落到
 * 真实接口的 queued。</p>
 *
 * <p>【401 判定：算异常，不算正常——真机验证时拍板的设计决策】
 * target 指向网关（8085）而 watchman 没配 token 时，探针永远收到 401。
 * 判断：401 既不能证明被监控服务活着，也不能当作"探测正常"忽略——它是
 * "监控通道本身断了"（配置错误或鉴权链路故障），对告警系统而言"失明"必须
 * 出声，否则真正的故障会被静默吞掉。所以保守记异常、走同一连续阈值建事件
 * 路径，且摘要里带上状态码（"探针收到 HTTP 401"），让人/诊断图能区分
 * "服务不可达"与"可达但探针被拒"。生产接法：给 watchman 配服务间 token，
 * 或像本次演示一样让 target 直连内网 judge-service（不过网关、无鉴权）。</p>
 *
 * <p>去重冷却：每个服务触发后 sentry.watchman.cooldown-ms 毫秒内不再重复建事件。
 * 意义：故障通常持续存在，没有冷却会每轮询一次建一个事件，把诊断队列变成
 * 自我 DDoS；冷却时长应显著大于一次诊断图的平均耗时（当前同步链路为秒级，
 * 默认给 5 分钟），又显著小于轮询周期×连续阈值之外的复发间隔。</p>
 *
 * <p>阈值默认值说明：queue-threshold=200 直接取计划书第 7 章剧本的告警线；
 * consecutive-times=3 比剧本的 5 次宽松（轮询间隔 5s 时 15s 确认窗口，
 * 演示节奏更快）；生产按 SLO 重新标定，这两个数就是调参入口。</p>
 *
 * <p>开关：sentry.watchman.enabled 默认 false——未部署 mini-judge 时轮询必然
 * 连续失败会疯狂建事件，所以缺省不装配本 Bean（@ConditionalOnProperty），
 * 演示手动 create 链路、单测都不受它干扰。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sentry.watchman.enabled", havingValue = "true")
public class Watchman {

    @Value("${sentry.target-base-url}")
    private String targetBaseUrl;

    /** 队列深度告警线（计划书剧本：320 > 200） */
    @Value("${sentry.watchman.queue-threshold:200}")
    private int queueThreshold;

    /** 连续异常次数阈值：达到即建事件 */
    @Value("${sentry.watchman.consecutive-times:3}")
    private int consecutiveTimes;

    /** 同一服务触发后的去重冷却毫秒数 */
    @Value("${sentry.watchman.cooldown-ms:300000}")
    private long cooldownMs;

    private final IncidentService incidentService;

    /** service -> 连续异常计数（轮询成功清零） */
    private final Map<String, Integer> consecutiveFails = new ConcurrentHashMap<>();
    /** service -> 上次自动建事件时刻（冷却判断用） */
    private final Map<String, Long> lastTriggerAt = new ConcurrentHashMap<>();

    /**
     * 从 status JSON 里抠队列深度数字——不引 JSON 库的正则，够且只够演示语义。
     * \d{1,9} 限长防溢出；两个字段各一条模式：queueDepth（计划书/mock 形态）
     * 优先，queued（mini-judge 真实接口字段）回落。
     */
    private static final Pattern QUEUE_DEPTH = Pattern.compile("\"queueDepth\"\\s*:\\s*(\\d{1,9})");
    private static final Pattern QUEUED = Pattern.compile("\"queued\"\\s*:\\s*(\\d{1,9})");

    public Watchman(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    /** 巡检周期由 sentry.watchman.poll-interval-ms 控制（默认 5s，fixedDelay=上轮结束起算） */
    @Scheduled(fixedDelayString = "${sentry.watchman.poll-interval-ms:5000}")
    public void poll() {
        String service = "judge-service";
        String reason = probeOnce(); // null 表示本轮正常

        if (reason == null) {
            consecutiveFails.remove(service);
            return;
        }
        int fails = consecutiveFails.merge(service, 1, Integer::sum);
        log.info("watchman probe abnormal service={} consecutive={} reason={}",
                service, fails, reason);
        if (fails < consecutiveTimes) {
            return; // 未达连续阈值，继续观察
        }

        // 冷却期内不重复建事件（阈值计数保留，出冷却后的下一次异常仍会尝试）
        long now = System.currentTimeMillis();
        Long last = lastTriggerAt.get(service);
        if (last != null && now - last < cooldownMs) {
            log.debug("watchman suppressed by cooldown: service={} last trigger {}ms ago",
                    service, now - last);
            return;
        }
        lastTriggerAt.put(service, now);
        consecutiveFails.remove(service);
        // 告警摘要即计划书 3.1"轮询异常自动发起"的人工可读版本，进 State 的 ALERT 通道
        incidentService.createAndRun(service,
                "watchman 自动告警：连续 " + fails + " 次异常，最近一次 " + reason);
    }

    /**
     * 单轮探测：返回 null=正常，返回非 null=异常原因文本。
     * package-private 以便单测直接断言各分支（含对真实 com.sun.net HttpServer 的探测）。
     */
    String probeOnce() {
        String body;
        try {
            body = RestClient.create()
                    .get().uri(statusUrl())
                    .retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            // 收到 HTTP 响应但状态码 4xx/5xx。判定见类注释"401 判定"：
            // 探针被拒=监控失明，保守记异常，摘要里带状态码以便区分"不可达"。
            return "探针收到 HTTP " + e.getStatusCode() + "："
                    + (e.getStatusCode().value() == 401
                       ? "监控通道鉴权失败（被监控服务可能健在，但探针读不到指标）"
                       : "被监控接口返回错误状态");
        } catch (Exception e) {
            // 连接失败/超时/DNS——目标不可达，本身即故障信号
            return "探针请求失败: " + e.getMessage();
        }
        return evaluateBody(body);
    }

    /** 响应体判定：队列深度超阈值或字段缺失 → 异常原因；正常返回 null */
    String evaluateBody(String body) {
        String text = body == null ? "" : body;
        // 注意：matcher.find() 每调一次就向后推进一个匹配，只能"判定+取值"用同一次结果，
        // 先试剧本形态的 queueDepth，再回落 mini-judge 真实字段 queued（见类注释）
        Matcher m = QUEUE_DEPTH.matcher(text);
        if (m.find()) {
            return judgeDepth(m.group(1));
        }
        m = QUEUED.matcher(text);
        if (m.find()) {
            return judgeDepth(m.group(1));
        }
        // 响应里没有可识别的队列深度字段：视为接口形态不符，保守记异常但原因如实写
        return "status 响应未含 queueDepth/queued 字段";
    }

    /** 深度对阈值判定：超限返回异常原因文本，未超限返回 null（正常） */
    private String judgeDepth(String digits) {
        int depth = Integer.parseInt(digits);
        return depth > queueThreshold
                ? "队列深度 queued/queueDepth=" + depth + "（阈值 " + queueThreshold + "）"
                : null;
    }

    /**
     * 拼接探针 URL。修复：配置项若带尾部斜杠（http://host:port/ 是很常见的手误），
     * 原来的直接拼接会得到 //api/judge/status——Tomcat 侧可容忍但网关/反向代理
     * 可能 404，表现为"目标活着却永远探测失败"。这里统一剥掉尾部斜杠。
     */
    String statusUrl() {
        String base = targetBaseUrl == null ? "" : targetBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/api/judge/status";
    }
}
