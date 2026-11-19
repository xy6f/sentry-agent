package com.sentryagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentryagent.service.IncidentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

/**
 * 飞书卡片按钮回调入口（不足清单第 6 项；docs/DEPLOY.md 第 4 节的预留位兑现）。
 *
 * <p>链路：飞书服务器 → POST /api/feishu/callback →（URL 验证 / 签名校验）→
 * 解析按钮 value → {@link IncidentService#approve}。卡片按钮的 value 结构
 * 与 {@link com.sentryagent.notify.FeishuChannel#approvalCardBody} 生成的对齐：
 * {@code {action: approve|reject, incident: N}}。</p>
 *
 * <p>飞书回调的两种形态（都要处理）：</p>
 * <ul>
 *   <li>URL 验证：后台保存回调地址时飞书先发一条含 challenge 字段的探测包，
 *       原样返回 challenge 即视为验证通过；</li>
 *   <li>按钮点击：含 header/token 字段 + 卡片按钮 payload，走签名校验后分派 approve。</li>
 * </ul>
 *
 * <p>签名校验（飞书官方算法）：hex(HMAC-SHA256(key=sentry.feishu.secret,
 * msg=timestamp + nonce + body)) 与请求头 X-Lark-Signature 恒等比对；
 * timestamp/nonce 取自 X-Lark-Request-Timestamp / X-Lark-Request-Nonce 头。
 * 关键实现细节：入参用 String 接原始 body（@RequestBody String）——签名必须
 * 对字节级原文计算，先反序列化成 Map 再重序列化会因键序漂移必然验签失败；
 * 解析后的 Map 只用于业务分派。校验抽成纯函数 {@link #verifySignature}，
 * 脱离 Spring 可单测。</p>
 *
 * <p>安全立场：secret 为空（默认）= 跳过校验并打 WARN——本地/无 secret 部署可用，
 * 但生产必须配置（见 DEPLOY.md 第 4 节），否则任何知道回调地址的人都能批准重启；
 * secret 非空时强制校验（URL 验证形态同样校验——飞书配了 Encrypt Key 后所有回调
 * 都带签名），失败一律 403，绝不放行。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/feishu")
public class FeishuCallbackController {

    /** 飞书回调携带的 action 取值 -> 是否批准 */
    private static final String ACTION_APPROVE = "approve";
    private static final String ACTION_REJECT = "reject";

    private final IncidentService incidentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 飞书应用签名密钥：默认空=跳过校验（WARN 提醒）；生产必须配置 */
    private final String secret;

    public FeishuCallbackController(
            IncidentService incidentService,
            @Value("${sentry.feishu.secret:}") String secret) {
        this.incidentService = incidentService;
        this.secret = secret == null ? "" : secret.trim();
        if (this.secret.isEmpty()) {
            // WARN 在启动时打一次：让"没配 secret"这件事在日志里可见，而不是静默裸奔
            log.warn("sentry.feishu.secret 未配置，/api/feishu/callback 跳过签名校验"
                    + "（仅限本地/内网演示；生产必须配置，见 docs/DEPLOY.md 第 4 节）");
        }
    }

    /**
     * 回调总入口。飞书的回调 payload 形态多样（URL 验证/按钮点击/事件订阅），
     * 解析成 Map 宽进严出：结构不符的按钮包直接 400，不猜不兜底伪造。
     */
    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature) {

        // ── 签名校验（secret 非空时强制，先于一切业务分派；失败 403）──
        if (!secret.isEmpty()) {
            if (!verifySignature(secret, timestamp, nonce, rawBody, signature)) {
                log.warn("feishu callback signature MISMATCH, reject 403");
                return ResponseEntity.status(403).body(Map.of("result", "FORBIDDEN"));
            }
        }

        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            log.warn("feishu callback body not json: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("result", "BAD_REQUEST"));
        }

        // ── 形态一：URL 验证（含 challenge 字段）→ 原样返回，飞书后台保存地址用 ──
        Object challenge = payload.get("challenge");
        if (challenge != null) {
            log.info("feishu callback url-verification, challenge echoed");
            return ResponseEntity.ok(Map.of("challenge", String.valueOf(challenge)));
        }

        // ── 形态二：按钮点击（header/token 字段 + 卡片按钮 payload）→ 分派审批 ──
        // value 三层结构都要看：
        // 1. 新协议：action 是对象，value 嵌在 action.value 里（approvalCardBody 形态）
        // 2. 扁平形态：action 是字符串（"approve"/"reject"），incident 直接在顶层
        //    （本地 curl 联调/自测脚本常用形态，Phase 验收时抓到的支持面缺口）
        // 3. 旧形态：顶层 value 携带对象
        Object value = payload.get("value");
        Object actionNode = payload.get("action");
        String flatAction = "";
        if (actionNode instanceof Map<?, ?> a) {
            value = a.get("value");
        } else if (actionNode != null) {
            flatAction = String.valueOf(actionNode);   // 扁平形态：action 为字符串
        }
        Object action = value instanceof Map<?, ?> v && v.get("action") != null
                ? v.get("action")
                : (flatAction.isEmpty() ? (value instanceof Map ? "" : flatAction) : flatAction);
        // 修正：扁平形态下 action 就是字符串本身；value 只在嵌套形态存在
        if (!flatAction.isEmpty()) {
            action = flatAction;
        }
        long incidentId = extractIncidentId(payload, value);

        boolean approveFlag = ACTION_APPROVE.equals(String.valueOf(action));
        if (!approveFlag && !ACTION_REJECT.equals(String.valueOf(action))) {
            log.warn("feishu callback unknown action={}, payload-keys={}", action, payload.keySet());
            return ResponseEntity.badRequest().body(Map.of("result", "BAD_REQUEST"));
        }
        if (incidentId <= 0) {
            log.warn("feishu callback bad incident id in value={}", value);
            return ResponseEntity.badRequest().body(Map.of("result", "BAD_REQUEST"));
        }
        log.info("feishu callback dispatch: incident={} action={}", incidentId, action);
        try {
            incidentService.approve(incidentId, approveFlag);
        } catch (IllegalArgumentException e) {
            // 事件不存在：approve 抛 IAE，转 404（与 IncidentController.decision 对齐）
            return ResponseEntity.status(404).body(Map.of("result", "NOT_FOUND"));
        }
        // 飞书契约：返回 SUCCESS 后卡片按钮置灰，防重复点击重复审批
        return ResponseEntity.ok(Map.of("result", "SUCCESS"));
    }

    /**
     * 签名校验纯函数（飞书官方算法，脱离 Spring 可单测）：
     * hex(HMAC-SHA256(key=secret, msg=timestamp + nonce + body))
     * 与 header X-Lark-Signature 恒等比较（时序安全，不用 == 防 timing attack）。
     *
     * @param secret    飞书应用密钥（空由调用方短路，不进本函数）
     * @param timestamp X-Lark-Request-Timestamp 头
     * @param nonce     X-Lark-Request-Nonce 头
     * @param body      参与签名的请求体原文（字节级，未重序列化）
     * @param signature 请求头携带的 hex 摘要
     * @return true = 校验通过
     */
    static boolean verifySignature(String secret, String timestamp, String nonce,
                                   String body, String signature) {
        if (secret == null || secret.isEmpty()
                || timestamp == null || nonce == null
                || body == null || signature == null || signature.isEmpty()) {
            return false;   // 缺任一要素都过不了（宁可错杀，签名校验没有"降级"）
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + nonce + body)
                    .getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(digest);
            return constantTimeEquals(expected, signature.trim());
        } catch (Exception e) {
            log.warn("feishu signature verify error: {}", e.getMessage());
            return false;
        }
    }

    /** 时序安全的字符串比较：逐位异或累积，长度差也计入，防逐字节探测 */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    /** incident 号解析：value.incident 优先，顶层兜底；解析失败返回 -1（转 400） */
    private static long extractIncidentId(Map<String, Object> payload, Object value) {
        Object n = value instanceof Map<?, ?> v ? v.get("incident") : null;
        if (n == null) {
            n = payload.get("incident");
        }
        if (n instanceof Number num) {
            return num.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(n));
        } catch (Exception e) {
            return -1L;
        }
    }
}
