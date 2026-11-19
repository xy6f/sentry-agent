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
 * <p>TTL 语义（不足清单第 2 项）：令牌不是"永不过期的通行证"——签发时记录
 * 过期时刻（sentry.approval.token-ttl-ms，默认 10 分钟），consume 时先查
 * "是否过期"再删表项。过期与"不存在/错令牌"必须可区分：写工具侧统一表现为
 * REFUSED（令牌已无效，行为一致），但调用方（FeishuCallbackController 等回调链路）
 * 可通过 {@link #consumeReason(String, Long)} 拿到过期原因做差异化处置
 * （如事件自动 ESCALATED 转人工）。</p>
 *
 * <p>演进（W3 接 Redis 改这里）：issued 换成 RedisTemplate 的
 * SETNX+TTL（令牌有效期即计划书 t_approval.expire_at 语义），consume 换成原子
 * GETDEL——一次性保证从"进程内 remove"升级为"跨实例原子弹出"，重启丢令牌的
 * 问题随 checkpoint 一起解决。接口形态 issue/consume 不变，调用方零改动。</p>
 *
 * <p>落库对齐说明（本轮注释钉死，不强制落库）：schema.sql 的 t_approval 已设计
 * expire_at 列（status 含 EXPIRED 枚举），与这里的内存过期语义一一对应。本轮只做
 * 内存过期、不写 t_approval——镜像写侧（IncidentPersistence）可能正被并行改动，
 * 避免冲突；W3 接 Redis 时 expire_at 随 SETNX TTL 天然对齐，届时补齐审批记录落库。</p>
 */
public class ApprovalRegistry {

    /** consume 结果原因码：查询侧用，注册表自身判断用布尔即可 */
    public enum ConsumeReason {
        /** 有效（且已消费销毁） */
        OK,
        /** 令牌不存在 / 已用过（一次性） / 与事件不绑定 */
        MISSING,
        /** 令牌存在但已过 expireAt */
        EXPIRED
    }

    /** 令牌有效期毫秒（sentry.approval.token-ttl-ms，默认 10 分钟），由 Spring 侧注入 */
    private final long ttlMs;

    /**
     * approvalId(token) -> 签发记录（绑定事件 + 过期时刻）；
     * 一次性，consume 即 remove。TTL 语义要求过期时刻与令牌同存，
     * 单值 Map<Long> 升级为小记录类（字段不可变，线程安全）。
     */
    private record TokenRecord(Long incidentId, long expireAt) {
    }

    private final Map<String, TokenRecord> issued = new ConcurrentHashMap<>();

    /** 兼容旧调用方/旧单测的构造：默认 TTL 10 分钟（与 sentry.approval.token-ttl-ms 缺省一致） */
    public ApprovalRegistry() {
        this(600_000L);
    }

    /** 正常装配入口：TTL 由配置注入（ms），负数视为非法回退默认值；0 合法（签发即过期，边界测试用） */
    public ApprovalRegistry(long ttlMs) {
        this.ttlMs = ttlMs >= 0 ? ttlMs : 600_000L;
    }

    /** 签发：仅审批链路调用（值班人点批准之后），返回一次性令牌 */
    public String issue(Long incidentId) {
        String token = UUID.randomUUID().toString();
        issued.put(token, new TokenRecord(incidentId, System.currentTimeMillis() + ttlMs));
        // 日志留白：common 层零依赖，签发日志由 core 层调用方记录
        return token;
    }

    /**
     * 消费：校验令牌存在、未过期、且绑定的是同一事件，成功即删除（防重放）。
     * 空令牌/错令牌/用过的令牌/过期令牌一律 false——写工具据此 REFUSED
     * （对写工具而言"令牌无效"的处置是同一种：拒绝执行，无需细分）。
     */
    public boolean consume(String token, Long incidentId) {
        return consumeReason(token, incidentId) == ConsumeReason.OK;
    }

    /**
     * 带原因的消费：供需要"区分过期 vs 不存在"的调用方（回调链路判过期转人工）使用。
     * 语义与 {@link #consume(String, Long)} 完全一致（同样一次性、成功即销毁），
     * 只是返回细分原因码。过期令牌同样被移除——过期的通行证没有留存价值。
     */
    public ConsumeReason consumeReason(String token, Long incidentId) {
        if (token == null || token.isEmpty()) {
            // 提前拦截：ConcurrentHashMap.remove(null) 会抛 NPE，而"没带令牌"是常态而非异常
            return ConsumeReason.MISSING;
        }
        TokenRecord rec = issued.remove(token);
        if (rec == null) {
            return ConsumeReason.MISSING;
        }
        if (System.currentTimeMillis() >= rec.expireAt()) {
            return ConsumeReason.EXPIRED;
        }
        return incidentId != null && incidentId.equals(rec.incidentId())
                ? ConsumeReason.OK
                : ConsumeReason.MISSING;
    }
}
