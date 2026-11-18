package com.sentryagent.notify;

import com.sentryagent.graph.IncidentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NotificationListener 的消息组装与路由单测（不依赖 IncidentService 真实例——传 null 模拟禁用）。
 */
class NotificationListenerTest {

    private NotificationListener listener() {
        // enabled=true + 空 channel 列表 + 惰性 provider（mock，getIfAvailable 返回 null 表示无事件）
        @SuppressWarnings("unchecked")
        ObjectProvider<com.sentryagent.service.IncidentService> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(null);
        return new NotificationListener(true, List.of(), provider);
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> snap = new HashMap<>();
        snap.put(IncidentState.INCIDENT_ID, 1L);
        snap.put(IncidentState.SERVICE, "judge-service");
        snap.put(IncidentState.ALERT, "queue backlog 320");
        snap.put(IncidentState.PLAN, "RESTART|judge-worker-1|HIGH|worker饱和");
        snap.put(IncidentState.REPORT, "根因: worker 池饱和");
        snap.put(IncidentState.TOTAL_TOKENS, 700);
        return snap;
    }

    @Test
    @DisplayName("跃迁定性：AWAITING_APPROVAL/ESCALATED=HIGH，CREATED=MID，CLOSED=LOW")
    void severityRouting() {
        NotificationListener l = listener();
        Map<String, Object> snap = snapshot();
        assertEquals(Severity.HIGH, l.buildMessage(1, "AWAITING_APPROVAL", snap).severity());
        assertEquals(Severity.HIGH, l.buildMessage(1, "ESCALATED", snap).severity());
        assertEquals(Severity.MID, l.buildMessage(1, "CREATED", snap).severity());
        assertEquals(Severity.LOW, l.buildMessage(1, "CLOSED", snap).severity());
    }

    @Test
    @DisplayName("AWAITING_APPROVAL 消息正文含审批引导与处置计划")
    void approvalMessageContainsPlan() {
        NotificationListener l = listener();
        NotificationMessage msg = l.buildMessage(1, "AWAITING_APPROVAL", snapshot());
        assertTrue(msg.detail().contains("RESTART|judge-worker-1"));
        assertTrue(msg.detail().contains("/api/incidents/1/decision"), "要给出审批入口");
        assertTrue(msg.title().contains("judge-service"));
    }

    @Test
    @DisplayName("缺字段容错：空快照不抛，service 回退 unknown")
    void tolerantToMissingFields() {
        NotificationListener l = listener();
        NotificationMessage msg = l.buildMessage(1, "CREATED", Map.of());
        assertEquals("unknown", msg.service());
        assertEquals(Severity.MID, msg.severity());
    }

    @Test
    @DisplayName("长正文截断到 800 字符内（短信/卡片体积护栏）")
    void detailTruncated() {
        NotificationListener l = listener();
        Map<String, Object> snap = snapshot();
        StringBuilder big = new StringBuilder("x".repeat(5000));
        snap.put(IncidentState.REPORT, big.toString());
        NotificationMessage msg = l.buildMessage(1, "CLOSED", snap);
        assertTrue(msg.detail().length() <= 810, "实际 " + msg.detail().length());
    }

    @Test
    @DisplayName("入口降级：provider 无 bean（通知关闭）时 onTransition 不抛")
    void onTransitionWithNullServiceSafe() {
        NotificationListener l = new NotificationListener(false, List.of(), providerForTest());
        assertDoesNotThrow(() -> l.onTransition(1, "CREATED"));
    }

    /** 测试辅助：把内部 provider 暴露给需要模拟"通知关闭"语义的用例 */
    ObjectProvider<com.sentryagent.service.IncidentService> providerForTest() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.sentryagent.service.IncidentService> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
