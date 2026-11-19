package com.sentryagent.controller;

import com.sentryagent.service.IncidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 飞书回调 Controller 单测（不足清单第 6 项的验收面）：
 * <ol>
 *   <li>URL 验证：challenge 原样透传，不触碰 IncidentService；</li>
 *   <li>无 secret（默认形态）：跳过签名校验，按钮回调直接分派 approve；</li>
 *   <li>secret 非空：错签名 403、缺签名头 403，正确签名放行（纯函数对拍官方算法）；</li>
 *   <li>action 解析：approve/reject 分别映射 incidentService.approve(id, true/false)，
 *       参数用 ArgumentCaptor 严格捕获验证。</li>
 * </ol>
 * 全程 mock IncidentService，不起 Spring、不跑图。
 * 请求体传原始 JSON 字符串（controller 按字节级原文验签，见主类注释）。
 */
class FeishuCallbackControllerTest {

    private static final String SECRET = "my-secret";

    private IncidentService incidentService;
    private FeishuCallbackController controller;

    /** 按钮回调原始 body（value 结构对齐 FeishuChannel.approvalCardBody） */
    private static String buttonBody(long incidentId, String action) {
        return "{\"header\":{\"event_type\":\"card.action.trigger\"},"
                + "\"token\":\"tok-placeholder\","
                + "\"action\":{\"tag\":\"button\",\"value\":{"
                + "\"action\":\"" + action + "\",\"incident\":" + incidentId + "}}}";
    }

    /** 按飞书官方算法计算 hex(HMAC-SHA256(secret, timestamp+nonce+body)) */
    private static String sign(String secret, String ts, String nonce, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((ts + nonce + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        incidentService = mock(IncidentService.class);
        controller = new FeishuCallbackController(incidentService, "");
    }

    @Test
    @DisplayName("URL 验证：challenge 原样透传，且绝不触碰 IncidentService")
    void challengeEchoedWithoutTouchingService() {
        var resp = controller.callback(
                "{\"challenge\":\"ajls38thkek\",\"type\":\"url_verification\"}",
                null, null, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("ajls38thkek", resp.getBody().get("challenge"),
                "challenge 必须原样返回");
        verify(incidentService, never()).approve(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("无 secret（默认形态）：跳过签名校验，approve 按钮以正确参数分派")
    void noSecretSkipsSignatureAndDispatches() {
        var resp = controller.callback(buttonBody(3L, "approve"), null, null, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("SUCCESS", resp.getBody().get("result"),
                "飞书契约：SUCCESS 后卡片按钮置灰");
        verify(incidentService).approve(eq(3L), eq(true));
    }

    @Test
    @DisplayName("reject 按钮：approve(id,false) 分派")
    void rejectDispatchesFalse() {
        controller.callback(buttonBody(4L, "reject"), null, null, null);
        verify(incidentService).approve(eq(4L), eq(false));
    }

    @Test
    @DisplayName("secret 非空：错签名一律 403，approve 不被调用")
    void wrongSignatureIs403() {
        controller = new FeishuCallbackController(incidentService, SECRET);
        var entity = controller.callback(buttonBody(5L, "approve"),
                "1700000000", "nonce1", "deadbeef");
        assertEquals(HttpStatus.FORBIDDEN, entity.getStatusCode(), "错签名必须 403");
        verify(incidentService, never()).approve(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("secret 非空且要素缺失（无签名头）：403 不放行")
    void missingSignatureHeaderIs403() {
        controller = new FeishuCallbackController(incidentService, SECRET);
        var entity = controller.callback(buttonBody(7L, "approve"),
                "1700000002", "n3", null);
        assertEquals(HttpStatus.FORBIDDEN, entity.getStatusCode());
        verify(incidentService, never()).approve(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("secret 非空：正确签名放行并分派（验签纯函数对拍官方算法）")
    void correctSignaturePasses() {
        controller = new FeishuCallbackController(incidentService, SECRET);
        String body = buttonBody(6L, "approve");
        String good = sign(SECRET, "1700000001", "n2", body);
        var entity = controller.callback(body, "1700000001", "n2", good);
        assertEquals(HttpStatus.OK, entity.getStatusCode(), "正确签名必须放行");
        assertEquals("SUCCESS", entity.getBody().get("result"));
        verify(incidentService).approve(eq(6L), eq(true));
    }

    @Test
    @DisplayName("验签纯函数对拍：摘要任一位变化/body 变化/secret 不同/要素缺失都失败")
    void verifySignaturePurityContract() {
        String body = buttonBody(6L, "approve");
        String good = sign(SECRET, "1700000001", "n2", body);
        assertTrue(FeishuCallbackController.verifySignature(
                        SECRET, "1700000001", "n2", body, good),
                "正确摘要必须通过校验");
        assertFalse(FeishuCallbackController.verifySignature(
                        SECRET, "1700000001", "n2", body, good.substring(0, 63) + "0"),
                "摘要任一位变化必须失败");
        assertFalse(FeishuCallbackController.verifySignature(
                        SECRET, "1700000001", "n2", body + " ", good),
                "body 变化必须失败");
        assertFalse(FeishuCallbackController.verifySignature(
                        "other-secret", "1700000001", "n2", body, good),
                "secret 不同必须失败");
        assertFalse(FeishuCallbackController.verifySignature(
                        SECRET, null, "n2", body, good), "缺 timestamp 必须失败");
        assertFalse(FeishuCallbackController.verifySignature(
                        SECRET, "1700000001", "n2", body, ""), "缺签名必须失败");
    }

    @Test
    @DisplayName("事件不存在：approve 抛 IAE → 404（与 decision 端点语义对齐）")
    void missingIncidentIs404() {
        when(incidentService.approve(eq(99L), anyBoolean()))
                .thenThrow(new IllegalArgumentException("事件不存在: 99"));
        var entity = controller.callback(buttonBody(99L, "approve"), null, null, null);
        assertEquals(HttpStatus.NOT_FOUND, entity.getStatusCode());
    }

    @Test
    @DisplayName("未知 action：400 BAD_REQUEST，不触碰 IncidentService")
    void unknownActionIs400() {
        var entity = controller.callback(buttonBody(8L, "maybe"), null, null, null);
        assertEquals(HttpStatus.BAD_REQUEST, entity.getStatusCode());
        verify(incidentService, never()).approve(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("参数捕获：approve 的 incidentId 与布尔位严格来自 value 结构")
    void dispatchParametersCaptured() {
        controller.callback(buttonBody(42L, "approve"), null, null, null);
        ArgumentCaptor<Long> idCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Boolean> flagCap = ArgumentCaptor.forClass(Boolean.class);
        verify(incidentService).approve(idCap.capture(), flagCap.capture());
        assertEquals(42L, idCap.getValue());
        assertEquals(true, flagCap.getValue());
    }

    @Test
    @DisplayName("非法 JSON body：400，不触碰 IncidentService")
    void malformedBodyIs400() {
        var entity = controller.callback("not-json{", null, null, null);
        assertEquals(HttpStatus.BAD_REQUEST, entity.getStatusCode());
        verify(incidentService, never()).approve(anyLong(), anyBoolean());
    }
}
