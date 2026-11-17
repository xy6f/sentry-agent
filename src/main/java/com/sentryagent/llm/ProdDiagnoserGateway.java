package com.sentryagent.llm;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;

/**
 * 生产网关：委托 LangChain4j AiServices 生成的 {@link Diagnoser} 代理。
 *
 * <p>存在意义不是"做点什么"，而是"什么都不做也要走同一扇门"——
 * 图侧只认 {@link DiagnoserGateway}，换实现=换配置开关（sentry.llm.enabled=true），
 * 零代码改动。这正是网关模式（Gateway/Anti-Corruption Layer）的用法。</p>
 *
 * <p>token 用量统计（README 遗留清单第 2 项）：AiServices 代理本身只吐
 * String，拿不到响应元数据，所以用量在 ChatModel 层挂 {@link ChatModelListener}
 * 收集——onResponse 回调携 ChatResponse，其 tokenUsage() 即本次调用的 TokenUsage。
 * 签名经 javap 反编译 1.20.2 字节码钉死（注意包名：1.20.2 里是 dev.langchain4j
 * 而非老版本的 org.dev.langchain4j，注释曾写错包名，以 javap 实测为准）：
 * ChatResponse.tokenUsage()（dev.langchain4j.model.chat.response.ChatResponse）
 * 返回 dev.langchain4j.model.output.TokenUsage，该类暴露
 * inputTokenCount()/outputTokenCount()/totalTokenCount()（均返回 Integer，
 * 可能为 null），另有静态 TokenUsage.sum 两参工具；ChatResponseMetadata
 * 亦携带同名 tokenUsage()。ChatModelListener 三方法均为 default，
 * 匿名实现合法。</p>
 *
 * <p>线程归属（同样经字节码确认）：Diagnoser 全部方法返回 String（同步 AiServices
 * 调用），同步链 ChatExecutor.execute() -> ChatModel.chat(ChatRequest) 默认方法 ->
 * ChatModelListenerUtils.onRequest/doChat/onResponse 全程在调用线程内完成，且工具
 * 循环（ToolService.executeInferenceAndToolsLoop）内的后续模型调用走的也是同一个
 * 同步 chat 入口——所以网关方法在委托前把 sessionId 放进 ThreadLocal、finally 清掉，
 * listener 在同线程的 onResponse 里读它归账即可，无需跨线程透传。</p>
 *
 * <p>装配位置见 {@link LlmConfig}：仅当 sentry.llm.enabled=true 时创建，
 * 避免无 key 环境下 OpenAI 客户端被拉进上下文；监听器在该装配处挂进
 * OpenAiChatModel.builder().listeners(...)。</p>
 */
@Slf4j
public class ProdDiagnoserGateway implements DiagnoserGateway {

    /** AiServices 动态代理：调用即发起模型请求 + function calling 循环 */
    private final Diagnoser diagnoser;

    /** 分账台账：listener 记账、tokensUsed 读账，与 Mock 网关同一份口径 */
    private final TokenLedger ledger;

    /**
     * 会话归账的线程上下文：网关方法委托前写入 sessionId（= "incident-{id}"），
     * listener 的 onResponse 在同一调用线程读出记账。委托完立即清除，防线程池
     * 复用线程串账（tomcat 工作线程会反复执行不同事件的网关调用）。
     */
    private final ThreadLocal<String> currentSession = new ThreadLocal<>();

    public ProdDiagnoserGateway(Diagnoser diagnoser, TokenLedger ledger) {
        this.diagnoser = diagnoser;
        this.ledger = ledger;
    }

    /**
     * 挂在 ChatModel 上的用量监听器：onResponse 取本次调用的 TokenUsage 记入台账。
     * provider 不回填 usage 时如实记 0（total 与 input/output 双双为 null），
     * 只回 input/output 时按合计口径手动求和——不编造数据是对取证工具的同一条纪律。
     */
    public ChatModelListener usageListener() {
        return new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext ctx) {
                // onRequest 阶段没有响应可记；sessionId 归账统一在 onResponse
            }

            @Override
            public void onResponse(ChatModelResponseContext ctx) {
                TokenUsage usage = ctx.chatResponse().tokenUsage();
                if (usage == null) {
                    return;
                }
                Integer total = usage.totalTokenCount();
                if (total == null) {
                    Integer in = usage.inputTokenCount();
                    Integer out = usage.outputTokenCount();
                    total = (in == null ? 0 : in) + (out == null ? 0 : out);
                }
                String sessionId = currentSession.get();
                if (sessionId != null && total != null) {
                    ledger.record(sessionId, total);
                }
            }
        };
    }

    @Override
    public String gatherEvidence(String service, String alert, Long incidentId, String sessionId) {
        currentSession.set(sessionId);
        try {
            return diagnoser.gatherEvidence(service, alert, incidentId, sessionId);
        } finally {
            currentSession.remove();
        }
    }

    @Override
    public String hypothesize(String evidence, String sessionId) {
        currentSession.set(sessionId);
        try {
            return diagnoser.hypothesize(evidence, sessionId);
        } finally {
            currentSession.remove();
        }
    }

    @Override
    public String verifyHypotheses(String hypotheses, String moreEvidence, String sessionId) {
        currentSession.set(sessionId);
        try {
            return diagnoser.verifyHypotheses(hypotheses, moreEvidence, sessionId);
        } finally {
            currentSession.remove();
        }
    }

    @Override
    public String makePlan(String rootCause, String sessionId) {
        currentSession.set(sessionId);
        try {
            return diagnoser.makePlan(rootCause, sessionId);
        } finally {
            currentSession.remove();
        }
    }

    @Override
    public String retrospect(Long incidentId, String transcript, String sessionId) {
        currentSession.set(sessionId);
        try {
            return diagnoser.retrospect(incidentId, transcript, sessionId);
        } finally {
            currentSession.remove();
        }
    }

    @Override
    public long tokensUsed(String sessionId) {
        return ledger.used(sessionId);
    }
}
