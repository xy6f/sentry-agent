# sentry-agent 部署手册

> 把"本机演示项目"变成"部署在你服务器上、守护你真实项目、手机收告警、一键审批自愈"的工具。
> 本手册吸收清言方案五/六（旁挂式三步部署 + 必坑清单），并按本项目实际实现做了修正。

## 1. 部署形态选择

| 形态 | 适用 | 资源 |
|---|---|---|
| 裸 JAR + systemd | 个人 VPS、1~2 台机器 | 1核2G |
| **Docker Compose（推荐）** | 监控多项目、要通知/公网回调 | 2核4G |
| Prometheus/Grafana 全家桶 | 50+ 项目、要指标大盘 | 8核16G |

本仓库提供 Compose 形态（`docker-compose.yml` + `Dockerfile` + `nginx.conf`）。

## 2. 三步跑通（旁挂式，被守护项目零改动）

sentry 是"旁挂式"监控——独立进程，靠**读日志 / 探活接口 / 看容器状态**了解你的项目。

### 第 1 步：sentry 跑起来

```bash
git clone https://github.com/xy6f/sentry-agent.git && cd sentry-agent
cp .env.example .env   # 填 MYSQL_ROOT_PASSWORD 等
docker compose up -d
curl http://localhost:8084/api/health    # {"status":"UP",...}
```

### 第 2 步：让它"看到"你的项目（三选一或组合）

1. **日志**（必做，零改动）：你的项目日志写到宿主机目录并挂载进容器：
   ```bash
   ln -s /path/to/your-app/app.log ./logs/your-app.log
   ```
2. **探活**（推荐）：`application.yml` 的 `sentry.watchman.rules` 加一条：
   ```yaml
   - service: your-app
     type: http-probe
     url: http://your-host:8080/actuator/health
   ```
   Spring Boot 项目加 `spring-boot-starter-actuator` 依赖即得 `/actuator/health`。
3. **指标**（进阶）：项目暴露 `/actuator/prometheus` + Prometheus 抓取后配 prometheus 规则。

### 第 3 步：手机收告警（飞书 5 分钟）

1. 飞书 PC 端 → 群设置 → 群机器人 → 添加"自定义机器人" → 复制 Webhook URL
2. `.env` 里 `SENTRY_NOTIFY_ENABLED=true`、`SENTRY_FEISHU_ENABLED=true`、`SENTRY_FEISHU_WEBHOOK=https://open.feishu.cn/open-apis/bot/v2/hook/xxx`
3. `docker compose up -d` 重建

LLM Key（诊断要调大模型）：`OPENAI_API_KEY` + `OPENAI_BASE_URL=https://api.deepseek.com/v1`（DeepSeek 比 OpenAI 便宜很多），`SENTRY_LLM_ENABLED=true`。

## 3. 实战验证（故意搞挂）

```bash
# 1. 杀掉你的项目（或断开探活目标）
# 2. 等 15~90 秒（连续 3 次异常 × 轮询周期）
# 3. sentry 自动建事件 + 飞书收告警
curl http://localhost:8084/api/incidents
# 4. 批准（手机飞书卡片或 API）
curl -X POST http://localhost:8084/api/incidents/1/decision -d '{"approve":true}'
# 5. 恢复项目 → CLOSED 复盘推送
```

## 4. 飞书交互审批卡片（公网部署后）

`FeishuChannel.approvalCardBody` 已生成卡片 JSON（批准/否决按钮，value 带 incidentId）。
按钮回调要求公网 HTTPS：

```
飞书服务器 → POST https://你的域名/api/feishu/callback → 校验飞书签名 → 调 decision 接口
```

**两道防线不可省**（清言方案安全点一）：回调侧校验飞书签名 ≈ 身份认证；消费一次性令牌 ≈ 操作授权。回调 Controller 是预留位（`IncidentController` 旁加一个端点即可），本地无法联调故未交付。

## 5. Docker 自愈的安全边界

写操作（restart/scale）有三层防线，**缺一不可**：

1. **HITL 令牌**：`approvalRegistry` 一次性消费（批准后才签发）；
2. **白名单**：只操作打了 `--label sentry-managed=true` 的容器；
3. **开关**：`SENTRY_DOCKER_ENABLED=true` 才真实执行（默认 MOCK 文案）。

⚠️ **不要直接挂 docker.sock**（等价宿主机 root）。生产用
[tecnativa/docker-socket-proxy](https://hub.docker.com/r/tecnativa/docker-socket-proxy)
限制 API 端点（CONTAINERS=1、POST=1、ALLOW_RESTARTS=1），sentry 侧只需
`DOCKER_HOST=tcp://socket-proxy:2375`（docker CLI 原生支持）。

## 6. 监控栈全家桶（可选）

需要指标大盘时，compose 追加：

```yaml
  prometheus:
    image: prom/prometheus:v2.54.1
    volumes: ["./prometheus.yml:/etc/prometheus/prometheus.yml"]
    ports: ["9090:9090"]
  grafana:
    image: grafana/grafana:11.2.0
    ports: ["3000:3000"]
```

sentry 的 prometheus 规则把 `prom-url` 指向 `http://prometheus:9090` 即可。
被守护项目只要暴露 `/actuator/prometheus`（Spring）或 `/metrics`（Go/Node）。

## 7. 必坑清单（八条）

1. **docker.sock 别直挂**——用 socket-proxy（见第 5 节）；
2. **SSE 反代必须 `proxy_buffering off`**——nginx.conf 已配，漏了前端进度会卡死；
3. **飞书回调要公网 HTTPS**——本地无法联调卡片按钮，先跑通 markdown 通知；
4. **通知异步降级**——`@Async` 已实现；自己扩展渠道时保持"网关抖动不卡状态机"；
5. **生产换 Redis checkpoint**——内存 MemorySaver 重启丢审批中事件（落库已解决事件底账）；
6. **Token 预算和 max-steps 生产必开**——防一次故障排查烧光 LLM 配额；
7. **JDK 17**——Spring Boot 3.5 硬要求，与被守护项目的 JDK 8/11 可共存；
8. **Maven 用阿里云镜像**——国内构建差 10 倍（settings.xml 已配）。

## 8. 新增一个被监控项目的标准操作（3 步，零代码）

1. 日志对齐：`ln -s /path/to/new-app.log ./logs/new-app.log`；
2. 加 Watchman 规则（探活/指标/关键字三选一）；
3. （可选自愈）给它的容器打 `--label sentry-managed=true`。

## 9. 资源规划参考

| 规模 | 配置 | 月成本参考 |
|---|---|---|
| 自用 ≤5 项目 | 2核4G VPS 一台全包 | ¥100 |
| 小团队 10~30 项目 | 4核8G + 独立 Redis/MySQL | ¥300 |
| 生产 50+ 项目 | 8核16G + Prometheus 集群 + Loki | ¥1000+ |
