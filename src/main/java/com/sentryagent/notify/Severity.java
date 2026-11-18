package com.sentryagent.notify;

/**
 * 通知严重级别（路由依据，对齐 Grafana 语义色）：
 * HIGH = 需要人立即行动（待审批 / 升级转人工）；MID = 需要知道（事件创建）；LOW = 仅供留档（闭环 / 成本熔断）。
 */
public enum Severity {
    HIGH, MID, LOW
}
