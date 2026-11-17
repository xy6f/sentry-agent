package com.sentryagent.llm;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * token 用量台账（W3 补齐 README 遗留清单第 2 项）：
 * 网关每完成一次模型调用就把本次消耗记入这里，按 sessionId（= "incident-{id}"）
 * 分账累加，图侧节点只管读——"谁花的钱记在谁的账上"。
 *
 * <p>为什么单独一个类而不是塞进网关实现：Mock 与 Prod 两套网关的记账语义必须
 * 一致（同一份 totalTokens 口径，测试断言与生产统计才有可比性），把台账从
 * 网关里抽出来，网关只负责"报告本次用了多少"，累加规则收敛在一处。</p>
 *
 * <p>线程模型：图节点是串行推进的（同一事件同一时刻只有一个节点在跑），
 * 但 approve 续跑与 SSE 订阅可能并发读，故分账桶用 ConcurrentHashMap，
 * 桶内累加用 AtomicLong——写方只有图执行线程，读方随时快照。</p>
 */
@Component
public class TokenLedger {

    /** sessionId -> 该事件累计消耗的 token 数（input+output 合计口径） */
    private final Map<String, AtomicLong> ledgers = new ConcurrentHashMap<>();

    /** 记一笔：本次模型调用的 token 消耗，累加进对应事件的分账桶 */
    public void record(String sessionId, long tokens) {
        if (sessionId == null || tokens <= 0) {
            return;
        }
        ledgers.computeIfAbsent(sessionId, k -> new AtomicLong())
                .addAndGet(tokens);
    }

    /** 读账：某事件当前累计 token 数（无账返回 0，不创建桶） */
    public long used(String sessionId) {
        AtomicLong bucket = sessionId == null ? null : ledgers.get(sessionId);
        return bucket == null ? 0L : bucket.get();
    }
}
