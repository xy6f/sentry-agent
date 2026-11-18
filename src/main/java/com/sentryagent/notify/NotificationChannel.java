package com.sentryagent.notify;

/**
 * 通知渠道 SPI：每条渠道决定自己关心哪些级别、收到消息怎么发。
 *
 * <p>设计约束（清言方案采纳的安全点二）：实现类必须自降级——发送失败只记日志，
 * 绝不向调用方抛异常；通知挂了不能阻塞诊断状态机。通用异步由
 * {@code NotificationListener} 统一处理，实现类专注"怎么发"。</p>
 */
public interface NotificationChannel {

    /** 渠道名（配置键与日志标识） */
    String name();

    /** 该渠道是否处理此级别（路由：如短信只收 HIGH，群消息收全部） */
    boolean supports(Severity severity);

    /** 是否启用（读配置，禁用时监听器直接跳过） */
    boolean enabled();

    /**
     * 发送。实现必须吞掉一切异常（try-catch + 日志），保证"渠道挂了不炸状态机"。
     * 返回 true 表示发送成功（供测试断言）。
     */
    boolean send(NotificationMessage msg);
}
