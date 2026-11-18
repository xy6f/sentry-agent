package com.sentryagent.notify;

import com.sentryagent.graph.IncidentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 事件通知监听器：状态机跃迁点统一出口（清言方案三·改造二的落点）。
 *
 * <p>挂载方式：IncidentService 在 create / approve(reject) / run 终态四处主动调用
 * {@link #onTransition}——不用 Spring @EventListener 是因为当前事件流经内存 Map 而非
 * 应用事件；接 MySQL 落库后（Phase 4）可改为发布领域事件 + @EventListener 解耦，接口不变。</p>
 *
 * <p>五条路由规则（severity 由跃迁点定性）：
 * CREATED → MID；AWAITING_APPROVAL → HIGH（附审批引导）；ESCALATED → HIGH；
 * CLOSED → LOW；token 预算熔断 → LOW（成本异常留档）。</p>
 *
 * <p>两个依赖纪律：
 * 1. 异步：@Async 吞掉一切异常——飞书/短信网关抖一下不能卡住诊断状态机（清言方案三·安全点二）；
 * 2. 惰性依赖：IncidentService ↔ 本类构造期循环（监听器要读事件快照），用 ObjectProvider
 *    打破——provider 只在真正发通知时（异步线程里）解析 bean，Spring 官方对这类
 *    "初始化期回调依赖"的标准解法。enable 总开关 sentry.notify.enabled 默认 false。</p>
 */
@Slf4j
@Component
public class NotificationListener {

    private final boolean enabled;
    private final List<NotificationChannel> channels;
    /** 惰性解析：打破 IncidentService → 本类 → IncidentService 的构造期环 */
    private final org.springframework.beans.factory.ObjectProvider<com.sentryagent.service.IncidentService> incidentServiceProvider;

    public NotificationListener(
            @Value("${sentry.notify.enabled:false}") boolean enabled,
            List<NotificationChannel> channels,
            org.springframework.beans.factory.ObjectProvider<com.sentryagent.service.IncidentService> incidentServiceProvider) {
        this.enabled = enabled;
        this.channels = channels;
        this.incidentServiceProvider = incidentServiceProvider;
    }

    /** 状态跃迁入口：由 IncidentService 在四个跃迁点调用 */
    @Async
    public void onTransition(long incidentId, String transition) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> snap = readSnapshot(incidentId);
            if (snap == null) {
                return;
            }
            NotificationMessage msg = buildMessage(incidentId, transition, snap);
            int sent = 0;
            for (NotificationChannel ch : channels) {
                if (ch.supports(msg.severity()) && ch.enabled() && ch.send(msg)) {
                    sent++;
                }
            }
            log.info("notification transition={} incident={} channels-sent={}/{}",
                    transition, incidentId, sent, channels.size());
        } catch (Exception e) {
            // 双保险：构建/路由阶段的异常同样只记日志
            log.warn("notification pipeline failed (degraded): {} incident={}",
                    e.getMessage(), incidentId);
        }
    }

    /** 惰性取 IncidentService（此刻 bean 早已装配完毕，不可能为 null） */
    private Map<String, Object> readSnapshot(long incidentId) {
        com.sentryagent.service.IncidentService svc = incidentServiceProvider.getIfAvailable();
        return svc == null ? null : svc.get(incidentId);
    }

    /** 跃迁点定性 + 消息组装（纯函数便于测试） */
    NotificationMessage buildMessage(long incidentId, String transition, Map<String, Object> snap) {
        String service = String.valueOf(snap.getOrDefault(IncidentState.SERVICE, "unknown"));
        String outcome = String.valueOf(snap.getOrDefault(IncidentState.OUTCOME, ""));
        String alert = String.valueOf(snap.getOrDefault(IncidentState.ALERT, ""));
        String report = String.valueOf(snap.getOrDefault(IncidentState.REPORT, ""));
        Object tokens = snap.get(IncidentState.TOTAL_TOKENS);
        Severity severity = switch (transition) {
            case "AWAITING_APPROVAL", "ESCALATED" -> Severity.HIGH;
            case "CREATED" -> Severity.MID;
            default -> Severity.LOW;   // CLOSED / TOKEN_BUDGET
        };
        String title = "sentry-agent [" + transition + "] 事件#" + incidentId + " " + service;
        StringBuilder detail = new StringBuilder();
        switch (transition) {
            case "AWAITING_APPROVAL" -> detail.append("诊断完成，等待人工审批。\n告警: ").append(alert)
                    .append("\n处置计划:\n").append(snap.getOrDefault(IncidentState.PLAN, "无"))
                    .append("\n→ 前端审批: http://localhost:5176  或 POST /api/incidents/")
                    .append(incidentId).append("/decision");
            case "ESCALATED" -> detail.append("转人工处理。\n结论: ").append(report);
            case "CLOSED" -> detail.append("自愈闭环完成。\n复盘: ").append(report);
            case "TOKEN_BUDGET" -> detail.append("Token 预算熔断，已转人工。累计: ").append(tokens);
            default -> detail.append("新事件创建。\n告警: ").append(alert);
        }
        if (detail.length() > 800) {
            detail.setLength(800);
        }
        return new NotificationMessage(incidentId, service, severity, transition,
                title, detail.toString(), Map.of("outcome", outcome,
                "tokens", String.valueOf(tokens == null ? 0 : tokens)));
    }
}
