package com.sentryagent.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 企业微信群机器人渠道（markdown webhook，纯 POST 半天量）。
 * 接入：群设置 → 群机器人 → 新建 → 复制 https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx。
 * 降级纪律同 FeishuChannel：异常只记日志。
 */
@Slf4j
@Component
public class WecomChannel implements NotificationChannel {

    private final boolean enabled;
    private final String webhook;
    private final RestClient rest;

    public WecomChannel(
            @Value("${sentry.notify.wecom.enabled:false}") boolean enabled,
            @Value("${sentry.notify.wecom.webhook:}") String webhook) {
        this.enabled = enabled;
        this.webhook = webhook;
        this.rest = (enabled && webhook != null && !webhook.isBlank())
                ? RestClient.create() : null;
    }

    @Override
    public String name() {
        return "wecom";
    }

    @Override
    public boolean supports(Severity severity) {
        return enabled;
    }

    @Override
    public boolean enabled() {
        return enabled && rest != null;
    }

    @Override
    public boolean send(NotificationMessage msg) {
        if (!enabled()) {
            return false;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("content",
                            "**" + msg.title() + "**\n" + msg.detail()));
            Map<?, ?> resp = rest.post()
                    .uri(webhook)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            boolean ok = resp != null && Integer.valueOf(0).equals(resp.get("errcode"));
            if (!ok) {
                log.warn("wecom send rejected: resp={} incident={}", resp, msg.incidentId());
            }
            return ok;
        } catch (Exception e) {
            log.warn("wecom send failed (degraded): {} incident={}",
                    e.getMessage(), msg.incidentId());
            return false;
        }
    }
}
