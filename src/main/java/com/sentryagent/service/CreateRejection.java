package com.sentryagent.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 建事件请求的两类"硬拒绝"结果码（任务 A，不足清单第 5 项）。
 *
 * <p>为什么不是抛异常：createAndRun 的正常返回是"事件快照"（Map），HTTP 面是 200
 * + JSON——"并发满/同服务进行中"是<b>业务裁决</b>而非程序故障，抛 IllegalArgumentException
 * 冒到容器变 500 反而把可处置信息（冲突类型、占用方事件号）丢掉了。这里显式建模
 * 两种拒绝原因，快照照常落 incidents 仓（可 GET 详情、可被后续终态断言消费），
 * outcome=REJECTED_* 让列表/前端按"未受理"过滤展示。</p>
 *
 * <p>两个语义层次（与 Watchman 冷却的双层防暴对应，见 IncidentService 类注释）：
 * REJECTED_BUSY 是<b>资源闸门</b>（全局并发上限，配额用尽即拒收，不排队）；
 * BUSY_DUPLICATE 是<b>语义闸门</b>（同服务同症状重复建事件，指向进行中的那个 id
 * 让调用方去订阅/审批既有事件，而不是再造一个）。两者都不重试、不排队——
 * 运维系统宁可拒收也不堆积。</p>
 */
public enum CreateRejection {

    /** 全局并发配额已满（sentry.incident.max-concurrent）：不排队直接拒收 */
    REJECTED_BUSY,

    /** 同服务已有进行中事件（RUNNING/AWAITING_APPROVAL）：BUSY_DUPLICATE，附带进行中事件号 */
    BUSY_DUPLICATE;

    /** 该拒绝码落库/展示用的 outcome 值（与 IncidentState.OUTCOME 同一值域） */
    public String outcomeValue() {
        return name();
    }

    /**
     * 组装一张"拒绝快照"：带 incidentId/service/alert/outcome/reason/conflictWith
     * 的最小 Map（不进图、不产生时间线——没有诊断发生就不得伪造诊断痕迹）。
     * incidentId 仍由 idGen 正常发号：拒绝也是审计事件，号必须连续可回查。
     */
    public static Map<String, Object> snapshot(long id, String service, String alert,
                                               CreateRejection reason, long conflictWith) {
        Map<String, Object> snap = new ConcurrentHashMap<>();
        snap.put(com.sentryagent.graph.IncidentState.INCIDENT_ID, id);
        snap.put(com.sentryagent.graph.IncidentState.SERVICE, service == null ? "" : service);
        snap.put(com.sentryagent.graph.IncidentState.ALERT, alert == null ? "" : alert);
        snap.put(com.sentryagent.graph.IncidentState.OUTCOME, reason.outcomeValue());
        snap.put("reason", reason == REJECTED_BUSY
                ? "并发配额已满（sentry.incident.max-concurrent），不排队直接拒收：运维系统宁可拒收也不堆积"
                : "同服务已有进行中事件，去重拒绝（等待/审批既有事件即可，不必重复建单）");
        if (reason == BUSY_DUPLICATE) {
            snap.put("conflictWith", conflictWith);
        }
        snap.put(com.sentryagent.graph.IncidentState.REPORT, "事件未受理：" + snap.get("reason"));
        return snap;
    }
}
