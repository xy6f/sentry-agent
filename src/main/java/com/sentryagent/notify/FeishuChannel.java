package com.sentryagent.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 飞书自定义机器人渠道（markdown 消息，webhook 一发即达）。
 *
 * <p>接入指引（个人 5 分钟可建）：飞书 PC 端 → 群设置 → 群机器人 → 添加"自定义机器人"
 * → 拿到 https://open.feishu.cn/open-apis/bot/v2/hook/{token} 填入配置。</p>
 *
 * <p>交互审批卡片（按钮回调 decision 接口）需要公网 HTTPS 地址才能在飞书后台保存，
 * 本地开发阶段先交付 markdown 文本通知；卡片 body 生成逻辑见 {@link #approvalCardBody(long, String)}
 * （纯函数、可单测），部署公网后把 webhook 换成卡片发送即可接上。</p>
 *
 * <p>降级纪律：发送异常只记日志返回 false，绝不向状态机抛——通知挂了不能阻塞诊断主流程。</p>
 */
@Slf4j
@Component
public class FeishuChannel implements NotificationChannel {

    private final boolean enabled;
    private final String webhook;
    private final RestClient rest;

    public FeishuChannel(
            @Value("${sentry.notify.feishu.enabled:false}") boolean enabled,
            @Value("${sentry.notify.feishu.webhook:}") String webhook) {
        this.enabled = enabled;
        this.webhook = webhook;
        // 飞书对空 webhook 会 400，干脆不建请求器；enabled 校验在 supports/send 前置
        this.rest = (enabled && webhook != null && !webhook.isBlank())
                ? RestClient.create() : null;
    }

    @Override
    public String name() {
        return "feishu";
    }

    @Override
    public boolean supports(Severity severity) {
        // 飞书收全部级别（个人值班主渠道）
        return enabled;
    }

    @Override
    public boolean enabled() {
        return enabled && rest != null;
    }

    @Override
    public boolean send(NotificationMessage msg) {
        if (!enabled()) {
            log.debug("feishu disabled, skip notification incident={} transition={}",
                    msg.incidentId(), msg.transition());
            return false;
        }
        try {
            // markdown 消息体：msg_type=text 最简；升级到交互卡片时换 msg_type=interactive
            Map<String, Object> payload = Map.of(
                    "msg_type", "text",
                    "content", Map.of("text", msg.title() + "\n" + msg.detail()));
            Map<?, ?> resp = rest.post()
                    .uri(webhook)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            boolean ok = resp != null && Integer.valueOf(0).equals(resp.get("code"));
            if (!ok) {
                log.warn("feishu send rejected: resp={} incident={}", resp, msg.incidentId());
            }
            return ok;
        } catch (Exception e) {
            // 降级纪律：渠道故障只记日志，不向状态机抛
            log.warn("feishu send failed (degraded): {} incident={}",
                    e.getMessage(), msg.incidentId());
            return false;
        }
    }

    /**
     * 审批卡片 body 生成（纯函数，公网部署后替换 msg_type=text 使用）。
     * 按钮点击后飞书回调 sentry 的 /api/incidents/{id}/decision —— 回调侧仍需校验
     * 飞书签名 + 消费一次性令牌，两道防线都不可省（否则等于把重启权限暴露给消息渠道）。
     */
    static Map<String, Object> approvalCardBody(long incidentId, String summary) {
        return Map.of(
                "msg_type", "interactive",
                "card", Map.of(
                        "header", Map.of("title", Map.of(
                                        "tag", "plain_text",
                                        "content", "事件 #" + incidentId + " 待审批"),
                                "template", "orange"),
                        "elements", new Object[]{
                                Map.of("tag", "div", "text", Map.of(
                                        "tag", "lark_md",
                                        "content", summary)),
                                Map.of("tag", "action", "actions", new Object[]{
                                        Map.of("tag", "button",
                                                "text", Map.of("tag", "plain_text", "content", "批准执行"),
                                                "type", "primary",
                                                "value", Map.of("action", "approve", "incident", incidentId)),
                                        Map.of("tag", "button",
                                                "text", Map.of("tag", "plain_text", "content", "否决"),
                                                "type", "danger",
                                                "value", Map.of("action", "reject", "incident", incidentId))})}));
    }
}
