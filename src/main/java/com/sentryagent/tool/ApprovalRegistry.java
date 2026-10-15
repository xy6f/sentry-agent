package com.sentryagent.tool;


import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审批令牌注册表（计划书 3.2"安全"的落点之一、第 5 章 Redis approval:{token} 的内存替身）：
 * 写操作 Tool（重启/扩容）执行前必须持有一次性令牌，令牌只能由审批接口颁发
 * （IncidentService.approve 签发后注入 State 的 APPROVAL 通道）。
 *
 * <p>关键立场（面试三层追问）：不信任 LLM 的自觉——把"是否已批准"做成
 * 框架层硬校验（consume 一次即失效，防重放），prompt 里的叮嘱只是第一道软防线。</p>
 *
 * <p>演进（W3 接 Redis 改这里）：issued 换成 RedisTemplate 的
 * SETNX+TTL（令牌有效期即计划书 t_approval.expire_at 语义），consume 换成原子
 * GETDEL——一次性保证从"进程内 remove"升级为"跨实例原子弹出"，重启丢令牌的
 * 问题随 checkpoint 一起解决。接口形态 issue/consume 不变，调用方零改动。</p>
 */
public class ApprovalRegistry {

    /** approvalId(token) -> incidentId；一次性，consume 即 remove */
    private final Map<String, Long> issued = new ConcurrentHashMap<>();

    /** 签发：仅审批链路调用（值班人点批准之后），返回一次性令牌 */
    public String issue(Long incidentId) {
        String token = UUID.randomUUID().toString();
        issued.put(token, incidentId);
        // 日志留白：common 层零依赖，签发日志由 core 层调用方记录
        return token;
    }

    /**
     * 消费：校验令牌存在、且绑定的是同一事件，成功即删除（防重放）。
     * 空令牌/错令牌/用过的令牌一律 false——写工具据此 REFUSED。
     */
    public boolean consume(String token, Long incidentId) {
        if (token == null || token.isEmpty()) {
            // 提前拦截：ConcurrentHashMap.remove(null) 会抛 NPE，而"没带令牌"是常态而非异常
            return false;
        }
        return incidentId != null && incidentId.equals(issued.remove(token));
    }
}
