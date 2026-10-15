package com.sentryagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AIOps 诊断 Agent 启动类（简历项目四）。
 *
 * <p>一句话架构：LangGraph4j 的状态图负责"流程确定性"（回环上限、审批中断、
 * checkpoint 恢复），LangChain4j 负责"智能"（LLM 推理与 @Tool 取证）。
 * 分工原则是本项目最重要的面试答案。</p>
 *
 * <p>@EnableScheduling：为 Watchman（自动巡检告警入口，计划书 3.1 Must 第一条）
 * 激活 @Scheduled 轮询。默认实现 sentry.watchman.enabled=false 不装配，
 * 开调度器本身零成本；演示手动链路（create 接口）不受影响。</p>
 */
@SpringBootApplication
@EnableScheduling
public class SentryAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentryAgentApplication.class, args);
    }
}
