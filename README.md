> Spring Boot 3.5 ｜ LangChain4j 1.20（模型与 @Tool）+ LangGraph4j 1.9（状态图）｜ Vue 3 作战大屏（前后端分离）

**设计目标**：把"值班工程师排障流程"做成一张**带状态、有环、可中断、可审计**的图——证据收集 → 假设生成 → 验证回环（上限熔断）→ 处置计划 → **人工审批（HITL）** → 执行自愈 → 复盘报告。高危操作由框架层一次性令牌硬校验，不信任 LLM 自觉；流程 Checkpoint 持久化，可断点恢复。被守护对象是同组织的 mini-judge 评测平台（见仓库列表）。

**功能速览**：9 节点状态图（条件边回环）、6 个白名单 @Tool（读日志/指标/容器，写操作需审批令牌）、审批面板、SSE 实时进度 + 降级轮询、事件历史重放（倍速）、Watchman 轮询自动建事件（冷却防风暴）；无 LLM Key 时 Mock 网关全流程可演示。

**快速开始**：

```bash
mvn -s settings.xml spring-boot:run     # 8084，默认 mock 模式零外部依赖
cd frontend && npm install && npm run dev   # 5176
# 真实 LLM：设置 OPENAI_API_KEY / OPENAI_BASE_URL 后以 -Dsentry.llm.enabled=true 启动
```

---

# sentry-agent —— AIOps 故障诊断与自愈 Agent（LangChain4j + LangGraph4j）

> LangGraph4j 状态图负责"流程确定性"（假设-验证回环、上限熔断、
> Human-in-the-loop 审批、全节点时间线审计），LangChain4j 负责"智能"
> （提示词契约 + @Tool 取证工具）。被守护对象：项目二 mini-judge 评测平台。
> 设计与分周的完整意图见 `项目计划书.md`。

当前完成度：**W1+W2 全量 + W4 的 SSE/Watchman/README**——
无 API key、无 Docker、无 Nacos 也能把全图跑通（本 README 演示链即验收路径）。
MySQL/Redis 持久化按计划书排在 W2-W3，代码以内存实现 + 接口抽象就位（见"生产接入清单"）。

---

## 1. 五分钟无 key 演示手册

前置：JDK 17、本仓 `maven-settings.xml`、8084 端口空闲。**全程不需要 OPENAI_API_KEY**
（`sentry.llm.enabled=false` 缺省 → 走 MockDiagnoserGateway 剧本回包，零网络）。

```bash
# 项目根目录（Git Bash）
MVN="/c/Users/12822/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0/bin/mvn"
"$MVN" -s ../maven-settings.xml spring-boot:run     # 起在 8084
```

另开终端按顺序执行（curl 即演示脚本，Windows Git Bash 自带）：

**① 灌入剧本日志**（写入 `./logs/judge-service.log`，含 `timeout killed` 关键行）

```bash
curl -s -X POST http://localhost:8084/api/dev/seed-logs \
  -H 'Content-Type: application/json' -d '{"service":"judge-service"}'
# 响应里 fetchServiceLogsPreview 已回读真实文件内容——读工具"真实化"当场可见
```

**② 发起诊断 → 事件挂起在审批闸口（pending，第一次 run）**

```bash
curl -s -X POST http://localhost:8084/api/incidents \
  -H 'Content-Type: application/json' \
  -d '{"service":"judge-service","summary":"队列深度 320 > 200 且爬升"}'
```

> Windows Git Bash 提示：若上面这条 curl 返回 400，是终端把中文 argv 转成了非 UTF-8 字节
> （客户端编码问题，服务端没错）。改用 ASCII 摘要即可，或把 JSON 写进文件用
> `--data-binary @file` 发送（本 README 冒烟实测后者/前者都通）。

预期响应关键片段（图走 取证→假设→二次取证验证→计划 后，在 HIGH 动作闸口停住）：

```json
{ "incidentId": 1, "outcome": "AWAITING_APPROVAL",
  "plan": "RESTART | judge-worker-1 | HIGH | ...", "steps": 6, ... }
```

**③ 看进度时间线 / 订阅 SSE**（第二次 run 前的值班人视角）

```bash
curl -s http://localhost:8084/api/incidents/1 | jq .timeline
curl -N http://localhost:8084/api/incidents/1/stream     # SSE：补发 6 条 step_done 后挂住等新增
```

**④ 人工批准 → 令牌注入 → 图幂等重放到 CLOSED（第二次 run）**

```bash
curl -s -X POST http://localhost:8084/api/incidents/1/decision \
  -H 'Content-Type: application/json' -d '{"approve":true}'
```

预期：`outcome=CLOSED`，`actions[0]` 含 `OK: container judge-worker-1 restart issued`
（一次性令牌被 execute 节点消费），`report` 为复盘报告。步骤 ③ 的 SSE 若还挂着，
会实时追到新 7 条 `step_done` 并收流 `done`。

**⑤ 看高危护栏的另一半：否决路径**

```bash
curl -s -X POST http://localhost:8084/api/incidents \
  -H 'Content-Type: application/json' -d '{"service":"judge-service","summary":"再演示一次"}'
curl -s -X POST http://localhost:8084/api/incidents/2/decision \
  -H 'Content-Type: application/json' -d '{"approve":false}'
# outcome=ESCALATED，report="人工否决自动处置，转人工处置"，绝无 actions
```

护栏硬证据：不带令牌直接调写工具必然 `REFUSED`（见单测 `ApprovalTokenGuardTest`）——
"是否已批准"是框架层校验，不依赖 prompt 自觉。

## 2. 跑测试（全 mock、不连网络）

```bash
"$MVN" -s ../maven-settings.xml test
```

覆盖面：PlanParser 合法/非法行与 NONE 语义；verifyRouter/approvalRouter 全分支
（含 steps 护栏优先级、"不成立"子串陷阱回归）；令牌护栏（REFUSED/一次性/事件绑定）；
max-steps 图级熔断；seed-logs→fetchServiceLogs 真实文件闭环；
端到端 create→AWAITING_APPROVAL→approve→CLOSED 两轮 run 状态断言。

## 3. 状态图与关键类速查

```
START → evidence → hypothesize → verify ─┬─ 成立 ────────────▶ plan → awaitApproval ─┬─ 有令牌/无动作 → execute|retrospect → retrospect → END
                     ▲                   └─ 不成立&未超限 ─────┐                       ├─ 无令牌 → pending → END(存档等审批)
                     └──────(回环：needsRegen 驱动重取证)◀────┘ escalate ◀─(判定耗尽/steps≥max-steps)
```

| 关注点 | 去哪读 |
|---|---|
| 图拓扑/节点函数 | `graph/IncidentGraphFactory` |
| 类型化状态通道（appender/累加/版本链） | `graph/IncidentState` |
| 条件边纯逻辑（单测对象） | `graph/IncidentRouters` |
| LLM 出口收口 + mock 剧本 | `llm/DiagnoserGateway` 与 `llm/MockDiagnoserGateway` |
| 6 个 @Tool（读真实化/写令牌护栏） | `tool/EvidenceTools`、`tool/ApprovalRegistry` |
| 事件编排/幂等重放 | `service/IncidentService` |
| 时间线 + SSE | `service/NodeProgressListener`、`controller/IncidentController#stream` |
| 自动告警入口 | `service/Watchman`（默认关） |
| dev 夹具接口 | `controller/DevToolsController` |

## 4. 配置参考（`application.yml`）

| 键 | 缺省 | 说明 |
|---|---|---|
| `sentry.llm.enabled` | `false` | 网关总开关：false=Mock 剧本、true=真实 LLM |
| `sentry.max-verify-loops` | 3 | 假设-验证回环圈数上限 |
| `sentry.max-steps` | 24 | 全局步数护栏，超限强制 escalate |
| `sentry.logs-dir` / `logs-tail-lines` | `./logs` / 50 | fetchServiceLogs 真实文件参数 |
| `sentry.watchman.enabled` | `false` | 自动巡检开关（阈值/冷却键见 Watchman 注释） |
| `sentry.dev-api.enabled` | `true` | seed-logs 等 dev 端点；生产置 false 摘除 |
| `sentry.sse.*` | 400ms/120s | 进度流轮询与寿命 |

## 5. 生产接入清单（各改哪里，一句话到位）

1. **真实 LLM**：`sentry.llm.enabled=true` + 环境变量 `OPENAI_API_KEY`/`OPENAI_BASE_URL`。
   装配点在 `llm/LlmConfig`（ChatModel → AiServices 代理 `Diagnoser` → `ProdDiagnoserGateway`），
   图零改动。Mock 剧本格式即提示词契约（`假设|验证方法`、`动作|目标|风险|理由`），
   模型不守格式时由 `PlanParser`/`HypothesisParser` 丢弃+日志兜底，失败路径落 escalate。
2. **Docker 真实执行**：`tool/EvidenceTools` 里 `getContainerStatus`/`listRecentDeploys`
   换 docker inspect/CI 查询；`restartContainer`/`scaleService` 通过令牌校验后的
   TODO(W3) 处接 Docker API，只允许 label=sentry-managed 白名单容器（计划书第 10 章）。
3. **checkpoint Redis**：`IncidentGraphFactory#incidentGraph` 末尾
   `CompileConfig.checkpointSaver(new MemorySaver())` 换成 langgraph4j 的 Redis saver
   （threadId=incidentId 即天然隔离）；配合 `IncidentService` 注释里的演进——
   幂等重放升级为 interrupt/updateState/resume 真断点续跑（kill -9 后从审批闸口恢复）。
4. **审批令牌 Redis**：`tool/ApprovalRegistry` 的内存 Map 换 SETNX+TTL / GETDEL
   （跨进程一次性 + expire_at 语义），接口不变。
5. **MySQL 持久化**：`service/IncidentService` 的内存 `incidents` Map 换
   t_incident/t_graph_step/t_evidence/t_approval/t_action_exec_log 落库（表结构在计划书第 5 章）；
   时间线写入点 `NodeProgressListener` 已收口，直接多挂一个 JPA 实现即可。
6. **Watchman 上线**：mini-judge 部署后 `sentry.watchman.enabled=true`，按 SLO 重标定
   `queue-threshold`/`consecutive-times`/`cooldown-ms`；`poll()` 里同步建事件
   换异步提交（接真实 LLM 后单事件分钟级，别占调度线程）。
7. **SSE 实时化**：`IncidentController#stream` 的"轮询时间线"换 graph.stream() 回调直推
   （注释已钉死升级位），前端不用改事件名。

## 6. 已知遗留（如实声明）

- Nacos 未接入：配置走本地 application.yml（与计划书无关紧要的差距）。
- token 用量统计（t_incident.total_tokens）未接：LangChain4j 响应元数据可取，留 W3。
- getMetrics 依赖 mini-judge 在线；不在时返回"查询失败"降级文本，图不中断。
- 评测方案（计划书第 8 章 10 剧本×对照基线）不在本轮范围。
