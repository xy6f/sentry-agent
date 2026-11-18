package com.sentryagent.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通知模块单测（Phase 1）。
 * 不打真实 webhook：飞书/企微渠道的"未启用"路径与消息组装逻辑全离线验证；
 * approvalCardBody 纯函数断言卡片结构（公网部署后接真卡片的契约钉死在这里）。
 */
class NotificationTest {

    /** 未启用（默认配置）时：supports=false、send 返回 false 且不抛——降级纪律的第一道 */
    @Test
    @DisplayName("飞书渠道默认禁用：不发送不抛异常")
    void feishuDisabledByDefault() {
        FeishuChannel ch = new FeishuChannel(false, "");
        assertFalse(ch.enabled());
        assertFalse(ch.supports(Severity.HIGH));
        assertFalse(ch.send(new NotificationMessage(1, "svc", Severity.HIGH,
                "AWAITING_APPROVAL", "t", "d", Map.of())));
    }

    @Test
    @DisplayName("企微渠道默认禁用：同上语义")
    void wecomDisabledByDefault() {
        WecomChannel ch = new WecomChannel(false, "");
        assertFalse(ch.enabled());
        assertFalse(ch.send(new NotificationMessage(1, "svc", Severity.LOW,
                "CLOSED", "t", "d", Map.of())));
    }

    @Test
    @DisplayName("短信渠道：只收 HIGH 级别，白名单为空时不发送")
    void smsRoutesOnlyHigh() {
        SmsChannel ch = new SmsChannel(true, "");
        assertTrue(ch.supports(Severity.HIGH));
        assertFalse(ch.supports(Severity.MID), "短信不打扰 MID/LOW");
        // 白名单空 → send 内部短路返回 false
        assertFalse(ch.send(new NotificationMessage(1, "svc", Severity.HIGH,
                "ESCALATED", "t", "d", Map.of())));
    }

    @Test
    @DisplayName("webhook 为空串时渠道视为未启用（防误打内部端点）")
    void blankWebhookCountsAsDisabled() {
        FeishuChannel ch = new FeishuChannel(true, "");
        assertFalse(ch.enabled(), "enabled=true 但 webhook 空 = 未配置，仍不发送");
    }

    @Test
    @DisplayName("飞书审批卡片 body：含 header 与批准/否决两个按钮，value 携带 incidentId")
    void approvalCardBodyStructure() {
        Map<String, Object> body = FeishuChannel.approvalCardBody(7, "队列积压 320>200");
        assertEquals("interactive", body.get("msg_type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) body.get("card");
        assertNotNull(card.get("header"));
        Object[] elements = (Object[]) card.get("elements");
        assertEquals(2, elements.length);
        @SuppressWarnings("unchecked")
        Map<String, Object> action = (Map<String, Object>) elements[1];
        Object[] buttons = (Object[]) action.get("actions");
        assertEquals(2, buttons.length);
        @SuppressWarnings("unchecked")
        Map<String, Object> approve = (Map<String, Object>) buttons[0];
        @SuppressWarnings("unchecked")
        Map<String, Object> approveValue = (Map<String, Object>) approve.get("value");
        assertEquals("approve", approveValue.get("action"));
        // Map.of 把 long 装箱为 Long；断言数值语义而非装箱类型
        assertEquals(7L, ((Number) approveValue.get("incident")).longValue());
    }

    @Test
    @DisplayName("RestClient 空引用：渠道未启用时 rest 不构建（构造器语义）")
    void restClientLazyWhenDisabled() throws Exception {
        // enabled=false 时构造器不会创建 RestClient —— 反射断言 rest 字段为 null
        FeishuChannel ch = new FeishuChannel(false,
                "https://open.feishu.cn/open-apis/bot/v2/hook/xxx");
        assertEquals("feishu", ch.name());
        java.lang.reflect.Field f = FeishuChannel.class.getDeclaredField("rest");
        f.setAccessible(true);
        assertNull(f.get(ch), "未启用渠道的 rest 必须保持 null（短路不建请求器）");
    }
}
