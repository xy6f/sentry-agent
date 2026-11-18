package com.sentryagent.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 短信渠道骨架：只发摘要 + 引导链接，用于 ESCALATED 这类必须叫醒人的场景。
 *
 * <p>真实发送需要云厂商 SDK + 充值 + 模板报备（阿里云 SMS 约 0.045 元/条），本轮只交付
 * 接口骨架与路由逻辑——phone-whitelist 白名单 + provider 抽象点已留好，
 * 接入时实现 {@link #dispatch(String, String)} 换成阿里云/腾讯云 API 调用即可。
 * 占位实现只打日志（等效于"短信已模拟发送"），方便全链路联调。</p>
 */
@Slf4j
@Component
public class SmsChannel implements NotificationChannel {

    /** 只给这些号码发（防误触费用）：配置 sentry.notify.sms.phone-whitelist */
    private final boolean enabled;
    private final List<String> phones;

    public SmsChannel(
            @Value("${sentry.notify.sms.enabled:false}") boolean enabled,
            @Value("${sentry.notify.sms.phone-whitelist:}") String phoneWhitelist) {
        this.enabled = enabled;
        this.phones = (phoneWhitelist == null || phoneWhitelist.isBlank())
                ? List.of() : List.of(phoneWhitelist.split(","));
    }

    @Override
    public String name() {
        return "sms";
    }

    /** 短信贵且打扰强：只收 HIGH（待审批 / 升级转人工） */
    @Override
    public boolean supports(Severity severity) {
        return enabled && severity == Severity.HIGH;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean send(NotificationMessage msg) {
        if (!enabled() || phones.isEmpty()) {
            log.debug("sms disabled or whitelist empty, skip incident={}", msg.incidentId());
            return false;
        }
        // TODO(生产接入): 换成阿里云 SMS API（access-key 走环境变量 SENTRY_SMS_KEY）
        // 模板示例：「sentry-agent: 事件#{} {} 请尽快处理 https://sentry.yourdomain.com/incidents/{}」
        log.info("[SMS-MOCK] to={} title={} incident={}", phones, msg.title(), msg.incidentId());
        return true;
    }

    /** 真实接入点：provider 抽象（阿里云/腾讯云各一个实现即可） */
    boolean dispatch(String phone, String text) {
        throw new UnsupportedOperationException("sms provider not configured");
    }
}
