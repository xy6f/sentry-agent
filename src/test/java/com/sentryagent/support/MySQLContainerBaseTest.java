package com.sentryagent.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * DB 依赖 @SpringBootTest 用例的公共基类（不足清单第 8 项"CI 无 DB 化"）。
 *
 * <p>子类只需 {@code extends MySQLContainerBaseTest}（配合元注解
 * {@link SpringBootTestWithMysql} 挂在本类上，@SpringBootTest + test profile +
 * 条件扩展自动继承），即可获得三级数据源回退：</p>
 *
 * <ol>
 *   <li>Docker 可用：手动启动一次性 mysql:8.0（镜像对齐 docker-compose.yml），
 *       {@link DynamicPropertySource} 把 datasource 三件套覆盖为容器地址，
 *       schema.sql 幂等建表，行为与本机 MySQL 完全一致；</li>
 *   <li>Docker 不可用、本机 MySQL 可达：不启动容器、不注册覆盖属性——
 *       Spring 自然使用 application-test.yml 的 localhost:3306 回退；</li>
 *   <li>两者皆无：{@link RequiresDockerCondition} 在上下文创建前整类 SKIP，
 *       本类代码根本不会执行。</li>
 * </ol>
 *
 * <p>容器不用 @Container/@Testcontainers 注解驱动而改为在
 * {@link DynamicPropertySource} 回调里按需启动：注解版容器无论配置源走哪条路
 * 都会强制起容器，与"本机 MySQL 回退"互斥；手动版只在容器路径被选中时启动，
 * 三种环境共用同一份代码。static 容器 + JDK ShutdownHook 兜底停止
 * （Testcontainers 的 Ryuk 守护进程同时兜底回收，双保险不留残留）。</p>
 */
public abstract class MySQLContainerBaseTest {

    /**
     * 一次性 MySQL 8.0 容器（lazy：仅容器路径被选中时才 start）。
     * 库名 sentry_agent 对齐 application.yml，建表脚本复用主资源 schema.sql
     * （幂等 IF NOT EXISTS）。密码随意设（sentry）——容器生命周期只有测试期，
     * 不落持久卷、不留任何残留。
     */
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("sentry_agent")
            .withUsername("sentry")
            .withPassword("sentry")
            .withInitScript("schema.sql")
            .withReuse(false);

    /** 容器是否已被本进程启动（避免多个测试上下文重复 start 同一容器） */
    private static volatile boolean started;

    /**
     * 把数据源灌进 Spring 环境。容器路径：注册容器三件套覆盖 yml；
     * 本机回退路径：什么都不注册，application-test.yml 的 localhost 配置自然生效。
     * 该回调在 Spring 上下文创建前执行，此时执行条件已放行
     * （Docker 可用 或 本机 MySQL 可达，二者必居其一）。
     */
    @DynamicPropertySource
    static void mysqlProps(DynamicPropertyRegistry registry) {
        if (DockerAvailability.isDockerAvailable()) {
            synchronized (MySQLContainerBaseTest.class) {
                if (!started) {
                    MYSQL.start();
                    started = true;
                    // Ryuk 之外再挂一个进程内兜底：JVM 退出时确保容器停止
                    Runtime.getRuntime().addShutdownHook(new Thread(MYSQL::stop,
                            "mysql-container-cleanup"));
                }
            }
            registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
            registry.add("spring.datasource.username", MYSQL::getUsername);
            registry.add("spring.datasource.password", MYSQL::getPassword);
        }
        // else：本机回退路径——不注册任何属性，application-test.yml 的
        // localhost:3306 + SPRING_DATASOURCE_PASSWORD 原样生效
    }
}
