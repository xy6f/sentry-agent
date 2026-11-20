package com.sentryagent.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * DB 依赖用例的跳过执行条件（CI 无 DB 化改造，不足清单第 8 项）。
 *
 * <p>放行规则：Docker 可用 <strong>或</strong> 本机 MySQL 可达 → 执行；
 * 两者皆无 → 跳过并给出人话原因。跳过（SKIP）不是失败（FAIL）——
 * 裸 CI 上这 16 个用例被标记 skipped 进 surefire 报告，其余纯逻辑用例
 * 照常执行、构建整体保持绿。</p>
 *
 * <p>"或"语义是三级回退的关键：本机开发机（Docker 未启动但装着 MySQL）
 * 必须照常全量执行（验收环境 a），不能因为 Docker 不在就误伤本地开发者；
 * 而执行路径内部再按 {@code DockerAvailability.isDockerAvailable()} 细分
 * 走容器还是走本机回退（见 {@code MySQLContainerBaseTest}）。</p>
 *
 * <p>实现为 JUnit 标准 SPI 的 {@link ExecutionCondition}，条件与"容器由谁
 * 管理"完全解耦——基类怎么演进这里都不用跟着改。</p>
 */
public class RequiresDockerCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        boolean docker = DockerAvailability.isDockerAvailable();
        boolean localMysql = DockerAvailability.isLocalMysqlReachable();
        if (docker || localMysql) {
            return ConditionEvaluationResult.enabled(docker
                    ? DockerAvailability.describe() + "，DB 用例由 Testcontainers 接管"
                    : "Docker 不可用但本机 MySQL 可达（localhost 回退），DB 用例照常执行");
        }
        return ConditionEvaluationResult.disabled(
                "Docker 不可用且本机 MySQL（localhost:3306）不可达，"
                        + "跳过依赖 DB 的用例（SKIP 不等于 FAIL，纯逻辑用例不受影响）");
    }
}
