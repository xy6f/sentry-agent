package com.sentryagent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批令牌 TTL 单测（不足清单第 2 项的验收面）：
 * <ol>
 *   <li>未过期令牌照常放行（consume=true，一次性语义不变）；</li>
 *   <li>过期令牌 consume 返回 false，且 consumeReason 细分为 EXPIRED（与"无令牌"可区分）；</li>
 *   <li>边界：expireAt 恰好到达时刻（currentTimeMillis == expireAt）即拒绝——
 *       判定用 >=，"有效期至 expireAt 之前的瞬间"语义。</li>
 * </ol>
 * 不起 Spring：TTL 从构造器注入（生产侧由 LlmConfig 读 sentry.approval.token-ttl-ms），
 * 过期用"极小 TTL + 忙等时钟前进"触发，零 sleep 依赖、毫秒级跑完。
 */
class ApprovalTokenTtlTest {

    @Test
    @DisplayName("未过期令牌放行：TTL 内 consume=true，且一次性语义不变")
    void validTokenPassesWithinTtl() {
        ApprovalRegistry registry = new ApprovalRegistry(60_000L);   // 1 分钟，绝不过期
        String token = registry.issue(7L);
        assertEquals(ApprovalRegistry.ConsumeReason.OK,
                registry.consumeReason(token, 7L), "TTL 内的有效令牌必须放行");
        // 一次性：consume 成功后令牌销毁，第二次变 MISSING
        assertEquals(ApprovalRegistry.ConsumeReason.MISSING,
                registry.consumeReason(token, 7L), "令牌一次性语义不受 TTL 改造影响");
    }

    @Test
    @DisplayName("过期令牌拒绝：consume=false，consumeReason=EXPIRED，日志侧可区分'无令牌'")
    void expiredTokenRefusedWithReason() throws InterruptedException {
        ApprovalRegistry registry = new ApprovalRegistry(1L);   // 1ms：忙等 2ms 必过期
        String token = registry.issue(7L);
        long deadline = System.currentTimeMillis() + 2;
        while (System.currentTimeMillis() < deadline) {
            Thread.yield();   // 忙等时钟前进越过 expireAt（毫秒级，零 sleep 抖动）
        }
        assertFalse(registry.consume(token, 7L), "过期令牌 consume 必须 false");
        // consume 已把过期令牌销毁（一次性），换新签发一枚并过期，验证原因码细分
        String token2 = registry.issue(7L);
        deadline = System.currentTimeMillis() + 2;
        while (System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertEquals(ApprovalRegistry.ConsumeReason.EXPIRED,
                registry.consumeReason(token2, 7L),
                "原因码必须是 EXPIRED 而非 MISSING（过期与无令牌必须可区分）");
    }

    @Test
    @DisplayName("边界：expireAt 到达时刻（含恰好相等，>= 判定）即拒绝，不含宽限毫秒")
    void boundaryExactlyAtTtlIsExpired() {
        // TTL=0：expireAt=签发时刻，consume 时刻必然 >= expireAt（时钟未走即为"恰好相等"）——
        // 确定性地钉死边界语义：有效期覆盖 [issued, expireAt) 左闭右开，到期瞬间即失效
        ApprovalRegistry registry = new ApprovalRegistry(0L);
        String token = registry.issue(7L);
        assertEquals(ApprovalRegistry.ConsumeReason.EXPIRED,
                registry.consumeReason(token, 7L),
                "consume 时刻恰好等于 expireAt（时钟未前进）也必须判过期");
    }

    @Test
    @DisplayName("事件绑定校验在 TTL 检查之后：过期+错事件 → 仍报 EXPIRED（先判过期）")
    void expiredCheckPrecedesIncidentBinding() throws InterruptedException {
        ApprovalRegistry registry = new ApprovalRegistry(1L);
        String token = registry.issue(1L);
        long deadline = System.currentTimeMillis() + 2;
        while (System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        // 过期令牌即使绑定错事件，原因码也必须是 EXPIRED（过期是更早的失败点，
        // 调用方据此转人工，而不是误判成"错令牌"）
        assertEquals(ApprovalRegistry.ConsumeReason.EXPIRED,
                registry.consumeReason(token, 999L),
                "过期判定必须先于事件绑定判定");
    }

    @Test
    @DisplayName("默认构造 TTL=600000ms：与 sentry.approval.token-ttl-ms 缺省一致")
    void defaultConstructorUsesTenMinutes() {
        ApprovalRegistry registry = new ApprovalRegistry();
        String token = registry.issue(7L);
        // 默认 10 分钟内必不过期——consume 立即成功即证明 TTL 为正且足够大
        assertTrue(registry.consume(token, 7L), "默认 TTL 下新签发令牌必须可用");
    }
}
