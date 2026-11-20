# CI 说明：测试的无 DB 化改造（不足清单第 8 项）

## 背景

改造前：9 个 `@SpringBootTest` 全上下文测试类（16 个用例）依赖本机 MySQL
（`application-test.yml` 与生产同源取密码），CI 机器没有 MySQL 就必然挂——
"本机能跑、CI 挂"是最典型的一次性工程债。

改造后：DB 依赖用例的数据源按**三级回退**自动择路，同一份代码在三种环境
下都是确定性行为，无需改任何配置：

```
Testcontainers 容器（Docker 可用）
        ↓ 容器起不来
本机 MySQL 回退（application-test.yml 的 localhost:3306）
        ↓ 本机 MySQL 也没有
整类 SKIP（RequiresDockerCondition 判定，SKIP ≠ FAIL）
```

## 改动清单（代码侧）

| 文件 | 作用 |
|---|---|
| `pom.xml` | 新增 `org.testcontainers:mysql` + `org.testcontainers:junit-jupiter`（**仅 test scope**，版本由 Boot 3.5.16 BOM 统一管理为 1.21.4） |
| `src/test/java/com/sentryagent/support/DockerAvailability.java` | 探测 Docker 可用性（`docker info` 退出码 + Server Version 校验），进程内缓存一次；`SENTRY_TEST_FORCE_NO_DOCKER=true` 可强制按无 Docker 处理（本地模拟验收用） |
| `src/test/java/com/sentryagent/support/RequiresDockerCondition.java` | JUnit `ExecutionCondition`：Docker 不可用 → DB 依赖用例整类 disabled，原因写进 SKIP 报告 |
| `src/test/java/com/sentryagent/support/MySQLContainerBaseTest.java` | 公共基类：static 一次性 `mysql:8.0` 容器（镜像对齐 docker-compose.yml）+ `@DynamicPropertySource` 覆盖 datasource 三件套；`withInitScript("schema.sql")` 幂等建表 |
| `src/test/java/com/sentryagent/support/SpringBootTestWithMysql.java` | 组合元注解：`@SpringBootTest + @ActiveProfiles("test") + 条件扩展 + @Testcontainers` |
| 9 个 `@SpringBootTest` 类 | 改为 `@SpringBootTestWithMysql` + `extends MySQLContainerBaseTest`（用例本体零改动） |
| `src/test/resources/application-test.yml` | 显式钉死本地回退 url（localhost:3306），注释说明三级回退语义 |

涉及类：`ApiContractRegressionTest`、`IncidentEndToEndMockTest`、
`IncidentRestartRecoveryTest`、`IncidentTerminalDecisionIdempotencyTest`、
`MaxStepsGuardTest`、`SeedLogsRoundTripTest`、`VerifyLoopGuardTest`、
`TokenBudgetGuardTest`、`TokenUsageAccountingTest`。

## 三环境验收矩阵

| 环境 | Docker | 本机 MySQL | DB 依赖用例（16 个） | 纯逻辑用例（95 个） | 结果 |
|---|---|---|---|---|---|
| a. 本地开发机 | 无所谓 | 可达 | 走本机 MySQL（回退路径） | 绿 | 全绿（111 全过） |
| b. 本地无 MySQL | 可用 | 不可达 | Testcontainers 拉起 mysql:8.0 | 绿 | 全绿（111 全过） |
| c. 裸 CI | 不可用 | 不可达 | **SKIP 16**（报告记 skipped） | 绿 | 绿（95 过 + 16 skip） |

说明：

- SKIP 是 JUnit 的 skip 语义，surefire 报告里记 `skipped`，不进 `Failures/Errors`，
  构建整体仍为 `BUILD SUCCESS`；
- 环境 c 的模拟方式：本机 `export SENTRY_TEST_FORCE_NO_DOCKER=true`（或停掉
  Docker Desktop 并停本机 MySQL 服务），跑 `mvn test` 应看到 16 skipped；
- Testcontainers 用例首次运行需拉取 `mysql:8.0` 镜像（约 170MB），CI 上建议缓存
  或使用镜像加速。

## CI 配置示例（GitHub Actions）

### 路线一：Testcontainers（推荐，与本仓改造直接匹配）

GitHub 托管 runner（ubuntu-latest）自带 Docker，零额外配置即可跑通：

```yaml
name: ci
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest        # 自带 Docker Engine，Testcontainers 开箱即用
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven            # 缓存 ~/.m2：testcontainers 构件与 mysql:8.0 层走加速

      # 无任何数据库 service —— DB 用例由 Testcontainers 拉起一次性 mysql:8.0，
      # 密码等敏感值不进仓库；纯逻辑用例在任何 runner 上都能跑
      - name: Run tests
        run: mvn -B test

      - name: Publish test report
        uses: actions/upload-artifact@v4
        if: always()              # 失败也要把 surefire 报告传上来定位
        with:
          name: surefire-reports
          path: target/surefire-reports/**
```

### 路线二：services.mysql（不引 Testcontainers 的传统形态，仅对比参考）

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    services:
      mysql:                      # job 级 MySQL service，localhost:3306 可达
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: ci-pass
          MYSQL_DATABASE: sentry_agent
        ports:
          - 3306:3306
        options: >-
          --health-cmd="mysqladmin ping -h localhost"
          --health-interval=10s --health-timeout=5s --health-retries=10
    env:
      SPRING_DATASOURCE_PASSWORD: ci-pass   # application-test.yml 同源取值
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven
      - run: mvn -B test
```

两条路线选型：

- **路线一**是本仓当前改造的默认形态：不依赖 runner 提供的 service 语法，
  换 CI 平台（GitLab CI / Jenkins / Gitee Go）只需换外壳，测试代码零改动；
  且 DB 用例在无 Docker 环境自动降级为 SKIP，永远不会因基础设施抖动而假失败。
- 路线二把 DB 生命周期交给 CI 平台，测试代码不感知——但要求 runner 必须有
  MySQL service 能力，且没有"无 DB 时优雅降级"的兜底。

## 本地复现三环境

```bash
M="/c/Users/12822/.m2/wrapper/dists/apache-maven-3.9.16/<hash>/bin/mvn"

# 环境 a：本机 MySQL 可用（现状）
export SPRING_DATASOURCE_PASSWORD=你的MySQL密码
"$M" -s settings.xml test

# 环境 b：MySQL 不可用 + Docker 可用 → Testcontainers 接管
#   （停掉本机 MySQL 服务后直接跑，无需改任何配置）
"$M" -s settings.xml test

# 环境 c：两者皆无 → DB 用例 SKIP、其余全绿
export SENTRY_TEST_FORCE_NO_DOCKER=true   # 无需真的停 Docker
"$M" -s settings.xml test
# 预期：Tests run: 111, Failures: 0, Errors: 0, Skipped: 16
```
