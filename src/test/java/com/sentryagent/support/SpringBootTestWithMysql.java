package com.sentryagent.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * "CI 无 DB 化"组合注解（不足清单第 8 项）：贴在 {@link MySQLContainerBaseTest}
 * 基类上，一次声明三件事——
 *
 * <ol>
 *   <li>{@code @SpringBootTest + @ActiveProfiles("test")}：沿用 test 配置源
 *       （seed-id-from-db=false 等单测口径不变）；</li>
 *   <li>{@code @ExtendWith(RequiresDockerCondition.class)}：Docker 与本机 MySQL
 *       皆不可用时整类跳过（SKIP 不等于 FAIL，CI 只少跑 DB 用例、不红构建）；</li>
 *   <li>{@code @Testcontainers}：激活 Testcontainers 扩展生命周期管理，容器本身
 *       与 datasource 动态覆盖定义在 {@link MySQLContainerBaseTest}（注解类型里
 *       不能放 static 方法与可变字段，这是 Java 语言的硬约束，故拆成"注解 + 基类"
 *       两件套）。</li>
 * </ol>
 *
 * <p>数据源优先级（叠加顺序）：Testcontainers 容器（Docker 可用）&gt; 本机 MySQL
 * （application-test.yml 的 localhost:3306 回退）&gt; 都没有 → 整类 SKIP。
 * 即验收矩阵的三种环境自动收敛，无需改任何 yml。</p>
 *
 * <p>各 DB 依赖测试类 {@code extends MySQLContainerBaseTest} 即完成接入；
 * 局部注解（@TestPropertySource / @Import / @AutoConfigureMockMvc 等）保留在
 * 子类上原样生效，Spring 会把子类与基类的注解合并求值。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(RequiresDockerCondition.class)
@Testcontainers
public @interface SpringBootTestWithMysql {
}
