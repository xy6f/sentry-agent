# 飞书告警接入手册（sentry-agent）

> 从零开始：注册飞书 → 建群 → 建机器人 → 收到第一条告警 → （可选）手机上点按钮审批。
> 全程约 15 分钟，免费，个人就能做。

## 0. 准备：注册飞书（已有账号跳过）

1. 手机应用商店下载 **飞书 App**（或访问 https://www.feishu.cn 注册）
2. 用手机号注册个人账号——个人免费版就够用，不需要企业认证
3. PC 端可选（https://www.feishu.cn/ 下载桌面版），后面建机器人用 PC 端操作更方便

## 1. 建一个接收告警的群

1. 飞书 App 或 PC 端 → **消息** 页签 → 右上角 **"+"** → **创建群组**
2. 群名随意（如「sentry 告警」），先不加其他成员（一个机器人就能用）
3. 建好后进入群聊

## 2. 建自定义机器人（核心步骤）

**PC 端操作**（手机端入口较深）：

1. 进入群聊 → 点右上角 **「…」（设置）** → **群机器人** → **添加机器人**
2. 选择 **「自定义机器人 Custom Bot」**
3. 填写：
   - 名称：`sentry-agent`（随意）
   - 描述：`AIOps 故障告警与审批`
4. 下一步后会显示 **Webhook 地址**，形如：
   ```
   https://open.feishu.cn/open-apis/bot/v2/hook/xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   ```
5. **复制这个地址**（这是唯一需要拿走的东西）

### 安全设置（三选一，推荐签名校验）

添加向导最后一步会让你选安全设置：

| 选项 | 说明 | 本项目建议 |
|---|---|---|
| 自定义关键词 | 消息必须含该词才被接收 | 可选：填 `sentry-agent`（我们的标题自带这个词，零配置直通） |
| **签名校验** | 最安全；需在 sentry 侧加签名逻辑 | 当前代码未实现——选这个会发不出消息，**别选**（要选需先扩展 FeishuChannel 加签名，代码约 20 行） |
| IP 白名单 | 只允许指定 IP 发送 | 本地开发 IP 会变，不适用 |

> **推荐：选「自定义关键词」填 `sentry-agent`。** 代码零改动即可跑通；
> 后续接交互卡片按钮时再升级到签名校验（卡片回调需要 verify token/secret）。

## 3. 把 Webhook 填进 sentry-agent

### 本机运行（mvn spring-boot:run）

```bash
# Windows (Git Bash)
export SENTRY_NOTIFY_ENABLED=true
export SENTRY_FEISHU_ENABLED=true
export SENTRY_FEISHU_WEBHOOK="https://open.feishu.cn/open-apis/bot/v2/hook/你的token"
mvn -s settings.xml spring-boot:run
```

### Docker Compose 部署

编辑 `.env` 文件：

```ini
SENTRY_NOTIFY_ENABLED=true
SENTRY_FEISHU_ENABLED=true
SENTRY_FEISHU_WEBHOOK=https://open.feishu.cn/open-apis/bot/v2/hook/你的token
```

然后 `docker compose up -d` 重建生效。

> ⚠️ Webhook 是秘密：别提交到 git（`.env` 已在 .gitignore），泄露后任何人都能往你群里发消息。

## 4. 收到第一条告警（验证）

发一个测试事件：

```bash
curl -X POST http://localhost:8084/api/incidents \
  -H "Content-Type: application/json" \
  -d '{"service":"demo-app","summary":"手工验证飞书链路"}'
```

**预期**：飞书群里几秒内收到一条消息——

```
sentry-agent [CREATED] 事件#1 demo-app
新事件创建。
告警: 手工验证飞书链路
```

走到审批闸口后还会收到第二条（`[AWAITING_APPROVAL]`，含处置计划与审批入口）。

**没收到？排查顺序**：
1. sentry 日志里搜 `feishu`——`send rejected` 表示飞书拒收（检查关键词安全设置），`send failed` 表示网络不通
2. Webhook 是否完整复制（`hook/` 后的 token 部分容易漏）
3. `SENTRY_NOTIFY_ENABLED` 和 `SENTRY_FEISHU_ENABLED` 是否都是 true（两个开关缺一不可）

## 5. 告警什么样子（路由规则）

| 事件状态 | 级别 | 你会收到吗 |
|---|---|---|
| 新事件创建 | MID | ✅ 群消息 |
| 诊断完成待审批 | HIGH | ✅ 群消息（含处置计划+审批入口） |
| 转人工（否决/熔断/未收敛） | HIGH | ✅ 群消息 |
| 自愈闭环完成 | LOW | ✅ 群消息（复盘报告） |

短信渠道只收 HIGH 且需要配置白名单（默认关，见 application.yml 的 `sentry.notify.sms`）。

## 6. 进阶：手机上点按钮审批（交互卡片）

> 前提：sentry 部署在**公网 HTTPS** 地址上（飞书回调硬要求，本地跑不通这步）。

### 现状说明

代码已实现卡片 JSON 生成（`FeishuChannel.approvalCardBody`：批准/否决两个按钮，value 携带事件 ID），但**缺一个回调 Controller**——因为按钮回调需要公网地址，本地无法联调。部署上云后补一个端点即可（约 30 行代码）：

```java
// 思路（部署后加到 IncidentController 旁）
@PostMapping("/api/feishu/callback")
public Map<String, Object> feishuCallback(@RequestBody Map<String, Object> payload) {
    // 1. 校验飞书签名（用机器人 secret，见飞书开放平台文档"事件订阅"）
    // 2. 解析 payload 里 button 的 value: {action: approve|reject, incident: N}
    // 3. 调 incidentService.approve(N, "approve".equals(action))
    // 4. 返回 {"result":"SUCCESS"} 让按钮变灰
}
```

### 部署到公网的三种方式

| 方式 | 成本 | 适用 |
|---|---|---|
| 云服务器 + 域名 + Let's Encrypt | ¥100/月起 | 长期生产 |
| Cloudflare Tunnel | 免费 | NAS/家庭内网，稳定性尚可 |
| ngrok / localtunnel | 免费 | 仅本地调试（地址每次变，飞书回调不稳定） |

## 7. 常见问题

**Q：个人版飞书能用机器人吗？**
A：能。自定义机器人是免费功能，个人群即可。

**Q：消息会发给我个人私聊吗？**
A：不会——自定义机器人只发到**它所在的群**。想私聊提醒，建一个只有自己的群。

**Q：怎么测试 Webhook 本身好不好使（不经 sentry）？**
A：直接 curl 飞书：
```bash
curl -X POST "你的Webhook地址" -H "Content-Type: application/json" \
  -d '{"msg_type":"text","content":{"text":"sentry-agent webhook 测试"}}'
```
返回 `{"code":0}` 即通。

**Q：一条告警都没收到，但 sentry 正常跑完了诊断？**
A：查跃迁点——通知在 create/approve 时触发；如果你是用 Watchman 自动建的，确认 `SENTRY_WATCHMAN_ENABLED` 也开着且 Watchman 真的建了事件（`GET /api/incidents` 看 id）。

**Q：想发到钉钉/Slack？**
A：实现 `NotificationChannel` 接口（照抄 WecomChannel，换 URL 和 payload 格式），Spring 自动装配进路由，配置加一段即可。
