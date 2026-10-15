# sentry-agent · 诊断作战大屏（Vue 3 版）

LangGraph4j AIOps 诊断自愈 Agent 的值班大屏。本版把原生三件套静态页重建为
**Vue 3 + Vite（纯 JavaScript，无 TypeScript）+ Element Plus（暗色）+ Pinia**，
拓扑动画体验与旧静态版（存档于 `legacy-static/`）逐帧对齐甚至更好。

## 快速开始

```bash
cd frontend
npm install          # .npmrc 已锁 registry=https://registry.npmmirror.com
npm run dev          # → http://localhost:5176（strictPort，端口不漂移）
npm run build        # → dist/（仓库已随附一份现成产物）
```

后端需运行在 `http://localhost:8084`（mock 模式即可演示，见根 README）。
dev 下 `/api/**` 由 vite proxy 透传到 8084，浏览器同源免 CORS；SSE 长连接经代理
**逐块流式转发不缓冲**（node-http-proxy 默认行为，`vite.config.js` 有注释与实测说明）。
API 日志抽屉里也可把 API_BASE 改成绝对地址直连后端（`CorsConfig.java` 对 `/api/**` 放开）。

## 结构

```
frontend/
├─ .npmrc                       # npmmirror 源
├─ vite.config.js               # port=5176 strictPort；/api → 8084（SSE 透传注释）
├─ index.html                   # <html class="dark">（Element Plus 暗色基座）
├─ src/
│  ├─ main.js                   # createApp + Pinia + ElementPlus + tokens.css
│  ├─ App.vue                   # 装配：顶栏/拓扑卡/三栏/历史表/抽屉
│  ├─ styles/tokens.css         # 设计令牌：#050b18 网格纹理、cyan→blue 能量、红/琥珀/绿呼吸
│  ├─ lib/topology.js           # 9 节点/14 边常量（节点名与 IncidentGraphFactory 逐字一致）
│  ├─ lib/parsers.js            # PlanParser/Hypothesis/verdict 行契约、证据徽章、模式推断（镜像后端）
│  ├─ api/sse.js                # SSE：fetch+ReadableStream 增量解析（替代 EventSource）
│  ├─ stores/incident.js        # 当前快照+timeline+重放节奏/(at|node)去重/动画期入队/决策链/历史
│  ├─ stores/apiLog.js          # API 流水 + API_BASE + 连通徽标
│  └─ components/
│     ├─ GraphTopology.vue      # 手绘 SVG 拓扑：props=timeline，增量点亮+描边走线+脉冲+琥珀呼吸+倍速
│     ├─ TopBar.vue             # MODE/WATCHMAN/API/终态 四徽标
│     ├─ EvidenceStream.vue     # 证据卡（首轮/补充、来源徽章）+ 假设判定卡（成立绿框）
│     ├─ ApprovalPanel.vue      # plan 四段解析动作列表 + 批准/否决 + 复盘报告
│     ├─ TimelineList.vue       # 节点时间线竖流（SSE 增量 append，自动滚底）
│     ├─ HistoryTable.vue       # 历史事件 el-table，点击载入重放
│     ├─ IncidentActions.vue    # 一键剧本链 + 手动创建事件 el-dialog
│     └─ ApiLogDrawer.vue       # el-drawer API 日志 + API_BASE 配置
├─ dist/                        # 构建产物
└─ legacy-static/               # 旧版原生三件套（app.js/app.css/index.html）功能对照基线
```

## 使用的后端接口（字段名以代码为准）

| 接口 | 前端用法 |
|---|---|
| `POST /api/dev/seed-logs` `{service}` | 「一键注入故障剧本」第一步。**代码里 SeedReq 只有 `service` 字段，没有 `scenario` 参数**——剧本内置于 DevToolsController（队列堆积脚本），按代码实况只传 service |
| `POST /api/incidents` `{service, summary}` | 创建事件；**同步跑图**，返回即 State 快照（mock 下秒级返回，outcome 常为 `AWAITING_APPROVAL`；快照不含 timeline） |
| `GET /api/incidents/{id}` | 快照 + `timeline:[{at,node,digest}]`，拓扑重放动画与三栏的数据源 |
| `GET /api/incidents/{id}/stream` | SSE 命名事件 `step_done{at,node,digest}`、`done{outcome}`、`error{message}`；时间线增量 append + 拓扑实时点亮 |
| `POST /api/incidents/{id}/decision` `{approve}` | 审批两大按钮；批准=签发一次性令牌续跑图（execute→retrospect），否决=直接终结转人工 |

State 快照关键字段：`incidentId/service/alert/evidences[]/hypotheses/verdict/plan/approvalToken/
actions[]/report/outcome/steps/evidenceSeq/hypoSeq/verdictSeq/needsRegen/verifyRound`。

## 核心机制

- **拓扑重放动画**：store 按倍速（慢 900ms / 1× 550ms / 4× 250ms）向 `timeline` 追加条目，
  `GraphTopology` 以 props 观察增量逐帧点亮。描边走线 = `mounted` 时实测各 path
  `getTotalLength()` 写入 `--len`，CSS `stroke-dashoffset` 从满到 0；同一条边被再次经过
  （回环/审批后的幂等重放第二轮）时以 `:key` 重挂 path 从头重跑动画。当前节点脉冲光环、
  前节点转常亮；`AWAITING_APPROVAL` 时 approval 节点琥珀呼吸、`CLOSED` retrospect 绿 +
  END 脉冲、`ESCALATED/FAILED` escalate 红呼吸。`pending→evidence` 这类无直达边的重入
  视为"从 START 重新入场"（与旧版同规则）。
- **SSE 与降级**：`fetch + ReadableStream` 手解 SSE 帧（`event:`/`data:` 字段、`\n\n` 分帧），
  命名事件按类型分派，增量 append 时间线并点亮拓扑。连接失败 / 服务端 120s
  `timeout-ms` 主动收流 / 代理切断 → 自动**降级 1s 轮询** `GET /api/incidents/{id}`，
  对 timeline 按 `(at|node)` 键去重增量渲染——条目与 SSE step_done 同源
  （NodeProgressListener），仅实时性降为轮询粒度；出现终态即停轮。
- **审批链**：决策返回新快照 → `syncDetail` 刷新三栏 → 若流已因超时断开则决策前自动重开，
  `done` 事件回来统一收流，拓扑跟着 execute→retrospect 实时推进到 CLOSED。
- **解析镜像**（全部镜像后端代码契约，非法内容不臆造）：
  - plan：按 `|` 切 4 段 `动作|目标|风险|理由`，动作白名单 `RESTART/SCALE/ROLLBACK/NONE`、
    风险 `LOW/HIGH`、目标非空，非法行**丢弃并记入 API 日志抽屉**（对齐 PlanParser 的 log.warn）；
  - hypotheses 行 `N. 假设 | 验证方法`，verdict 行 `N | 成立|不成立 | 依据`，序号对齐——
    "成立"的假设卡绿框、"不成立"灰化；
  - 证据卡：`[首轮证据]` 前缀 → 首轮徽章，否则补充；来源徽章按 `logs[ / metrics[ /
    containers / deploys[` 特征词推断；含"验证项"追加 BY-VERIFY-METHOD 徽章。
- **模式徽标**：后端无状态查询接口，按首个事件回包特征词推断（`（mock）`/`[首轮证据]` →
  MOCK 绿；有 evidences 且非 mock 特征 → 真实 LLM 黄）；WATCHMAN 徽标展示 yml 默认关（悬停有说明）。
- **历史事件**：localStorage 持久 50 条（键 `sentry_history_v1`，与旧版同键；API_BASE 键
  `sentry_api_base`），el-table 点击行或「▶ 重放」载入并重跑拓扑动画。

## 交互一览

1. **⚡ 一键注入故障剧本**：seed-logs → 创建事件 → 详情时间线驱动拓扑重放 →
   审批面板琥珀呼吸，等值班决策。
2. **＋ 手动创建事件**（el-dialog）：service/summary 可对接其它被守护服务名。
3. 批准执行 / 否决转人工 → decision 接口 → SSE 实时推进拓扑到 CLOSED/ESCALATED。
4. 拓扑上方「慢 / 1× / 4×」调整重放节奏；底部历史表点击重放。
5. 左下「API 日志」抽屉：每个请求/响应/SSE 事件/PlanParser 镜像丢弃行一行，可改 API_BASE。

## 与旧版（legacy-static）功能对照

全部功能均已迁移；差异仅在执行方式（手写 DOM → Vue 响应式 + Element Plus 组件）。
详见根交付清单"功能对照表"。旧版页面仍可浏览器直开使用（API 直连 8084）。
