package com.sentryagent.watchman;

import com.sentryagent.service.IncidentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watchman 多服务多规则巡检引擎（Phase 2：清言方案三·改造一落地）。
 *
 * <p>演进说明：原实现是"硬编码 judge-service 单目标 + 队列深度阈值"，只对 mini-judge
 * 有效。本版把巡检对象做成配置驱动的规则列表 {@link WatchmanRule}（三种类型：
 * http-probe / prometheus / log-keyword），判定逻辑拆到 {@link RuleEvaluator}
 * （可单测的纯函数），本类只剩调度与"连续异常→冷却→建事件"的状态管理。</p>
 *
 * <p>向后兼容（既有的 10 个 WatchmanTest 与旧行为不能破）：未配置
 * sentry.watchman.rules 时，自动回落为内置默认规则——
 * {service=judge-service, url={target-base-url}/api/judge/status, 队列阈值 queue-threshold}，
 * 判定语义（queueDepth/queued 双字段、401 判异常、尾部斜杠修复）由
 * RuleEvaluator.judgeQueueDepth 与 http-probe 分支原样承接。</p>
 *
 * <p>新增被监控项目 = 在 application.yml 加一段 rules 配置，核心代码零改动
 * （清言："新增一个项目 = 加一段 YAML 配置"的落地）。</p>
 *
 * <p>状态管理不变：service#type 规则键独立计数/冷却——同服务可挂多条规则，
 * 一条规则触发即建事件，事件摘要带上规则 key 便于归因。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sentry.watchman.enabled", havingValue = "true")
public class Watchman {

    @Value("${sentry.target-base-url}")
    private String targetBaseUrl;

    /** 旧配置兼容：未配 rules 时的队列阈值（原 queue-threshold） */
    @Value("${sentry.watchman.queue-threshold:200}")
    private int queueThreshold;

    @Value("${sentry.watchman.consecutive-times:3}")
    private int consecutiveTimes;

    @Value("${sentry.watchman.cooldown-ms:300000}")
    private long cooldownMs;

    /** 规则列表：Spring 把 sentry.watchman.rules 绑定为 List<WatchmanRule>；空 = 默认规则 */
    @Value("${sentry.watchman.rules:}")
    private List<WatchmanRule> rules;

    private final IncidentService incidentService;
    private final RuleEvaluator evaluator;

    /** 规则键 -> 连续异常计数 */
    private final Map<String, Integer> consecutiveFails = new ConcurrentHashMap<>();
    /** 规则键 -> 上次自动建事件时刻（冷却） */
    private final Map<String, Long> lastTriggerAt = new ConcurrentHashMap<>();

    public Watchman(IncidentService incidentService, RuleEvaluator evaluator) {
        this.incidentService = incidentService;
        this.evaluator = evaluator;
    }

    /** 巡检周期：sentry.watchman.poll-interval-ms（默认 5s） */
    @Scheduled(fixedDelayString = "${sentry.watchman.poll-interval-ms:5000}")
    public void poll() {
        for (WatchmanRule rule : effectiveRules()) {
            pollRule(rule);
        }
    }

    /** 生效规则集：显式配置优先；为空回落内置默认（judge-service 队列，旧行为兼容） */
    public List<WatchmanRule> effectiveRules() {
        if (rules != null && !rules.isEmpty()) {
            return rules;
        }
        return List.of(new WatchmanRule(
                "judge-service", WatchmanRule.TYPE_HTTP_PROBE,
                stripTrailingSlash(targetBaseUrl) + "/api/judge/status",
                null, null, queueThreshold, List.of()));
    }

    void pollRule(WatchmanRule rule) {
        String key = rule.key();
        String reason = evaluator.evaluate(rule);   // null=正常

        if (reason == null) {
            consecutiveFails.remove(key);
            return;
        }
        int fails = consecutiveFails.merge(key, 1, Integer::sum);
        log.info("watchman abnormal rule={} consecutive={} reason={}", key, fails, reason);
        if (fails < consecutiveTimes) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastTriggerAt.get(key);
        if (last != null && now - last < cooldownMs) {
            log.debug("watchman suppressed by cooldown: rule={} last {}ms ago", key, now - last);
            return;
        }
        lastTriggerAt.put(key, now);
        consecutiveFails.remove(key);
        incidentService.createAndRun(rule.service(),
                "watchman 自动告警 [" + rule.type() + "]: 连续 " + fails + " 次异常，最近一次 " + reason);
    }

    public static String stripTrailingSlash(String s) {
        String base = s == null ? "" : s.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }
}
