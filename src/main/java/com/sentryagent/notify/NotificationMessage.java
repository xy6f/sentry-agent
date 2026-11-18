package com.sentryagent.notify;

import java.util.Map;

/**
 * 通知消息载体：一次状态跃迁对应一条消息。
 *
 * @param incidentId  事件 ID
 * @param service     被守护服务名
 * @param severity    严重级别（渠道路由依据）
 * @param transition  跃迁点标识（CREATED / AWAITING_APPROVAL / ESCALATED / CLOSED / TOKEN_BUDGET）
 * @param title       单行标题（短信/推送用）
 * @param detail      详情文本（markdown 支持，卡片正文用）
 * @param extra       附加键值（如 rootCause、actions 摘要）
 */
public record NotificationMessage(long incidentId, String service, Severity severity,
                                  String transition, String title, String detail,
                                  Map<String, String> extra) {
}
