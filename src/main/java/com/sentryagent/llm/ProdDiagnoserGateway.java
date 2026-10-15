package com.sentryagent.llm;

import lombok.RequiredArgsConstructor;

/**
 * 生产网关：原样委托 LangChain4j AiServices 生成的 {@link Diagnoser} 代理。
 *
 * <p>存在意义不是"做点什么"，而是"什么都不做也要走同一扇门"——
 * 图侧只认 {@link DiagnoserGateway}，换实现=换配置开关（sentry.llm.enabled=true），
 * 零代码改动。这正是网关模式（Gateway/Anti-Corruption Layer）的用法。</p>
 *
 * <p>装配位置见 {@link LlmConfig}：仅当 sentry.llm.enabled=true 时创建，
 * 避免无 key 环境下 OpenAI 客户端被拉进上下文。</p>
 */
@RequiredArgsConstructor
public class ProdDiagnoserGateway implements DiagnoserGateway {

    /** AiServices 动态代理：调用即发起模型请求 + function calling 循环 */
    private final Diagnoser diagnoser;

    @Override
    public String gatherEvidence(String service, String alert, Long incidentId, String sessionId) {
        return diagnoser.gatherEvidence(service, alert, incidentId, sessionId);
    }

    @Override
    public String hypothesize(String evidence, String sessionId) {
        return diagnoser.hypothesize(evidence, sessionId);
    }

    @Override
    public String verifyHypotheses(String hypotheses, String moreEvidence, String sessionId) {
        return diagnoser.verifyHypotheses(hypotheses, moreEvidence, sessionId);
    }

    @Override
    public String makePlan(String rootCause, String sessionId) {
        return diagnoser.makePlan(rootCause, sessionId);
    }

    @Override
    public String retrospect(Long incidentId, String transcript, String sessionId) {
        return diagnoser.retrospect(incidentId, transcript, sessionId);
    }
}
