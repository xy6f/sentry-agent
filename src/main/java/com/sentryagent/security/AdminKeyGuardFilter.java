package com.sentryagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 管理操作 API Key 守卫（第四批 4.1：不足清单第 11 项"公网裸奔"的最小纵深）。
 *
 * <p>保护对象：审批决策（decision）与评测跑批等"改变系统状态/消耗资源"的端点。
 * 这些接口在本地演示形态无需鉴权；一旦按 docs/DEPLOY.md 暴露公网，
 * 任何人都能 POST decision 批准重启生产容器——HITL 闸口形同虚设。
 * 本过滤器提供最小可用的一刀切：header {@code X-Admin-Key} 必须等于配置值。</p>
 *
 * <p>开关语义（诚实的分层安全说明）：{@code sentry.security.admin-key} 为空（默认）
 * = 通道关闭、行为与今天完全一致（本地演示与回归零影响），但启动打一次 WARN 提醒
 * 公网部署必配；配置非空 = 强制校验，缺失/错误一律 401 JSON。
 * 它是"传输层鉴权"（谁在调用），与审批一次性令牌（这次操作的授权）是正交的两道防线：
 * key 被盗≠可直接执行——仍要过 decision 流程签发并消费令牌。生产最终形态是 OIDC/网关
 * 统一认证，本类是够用的第一步（对齐"API Key 放配置中心/环境变量，勿入仓库"纪律）。</p>
 */
@Slf4j
@Component
public class AdminKeyGuardFilter extends OncePerRequestFilter {

    private final String adminKey;
    /** 受保护路径：POST 决策与评测跑批（GET 查询面保持开放，大屏只读需要） */
    private static final List<String> PROTECTED_PREFIXES = List.of(
            "/api/incidents/");

    public AdminKeyGuardFilter(@Value("${sentry.security.admin-key:}") String adminKey) {
        this.adminKey = adminKey == null ? "" : adminKey.trim();
        if (this.adminKey.isEmpty()) {
            log.warn("sentry.security.admin-key 未配置：管理端点鉴权通道关闭——"
                    + "公网部署（docs/DEPLOY.md）前必须配置，否则任何人可审批自愈操作");
        } else {
            log.info("admin-key guard ENABLED for decision/eval endpoints");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 空 key=通道关闭（缺省零行为变化）；查询面放行、写面与跑批受控
        if (adminKey.isEmpty() || !isProtected(request)) {
            chain.doFilter(request, response);
            return;
        }
        String given = request.getHeader("X-Admin-Key");
        // 时序安全比较：长度先判后逐字比，避免按首字节差异提前返回的计时侧信道
        if (given == null || !constantTimeEquals(adminKey, given.trim())) {
            log.warn("admin-key rejected: uri={} remote={}", request.getRequestURI(),
                    request.getRemoteAddr());
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":\"FORBIDDEN_ADMIN_KEY\",\"message\":\"管理端点需要正确的 X-Admin-Key\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 受保护判定：审批决策=POST /api/incidents/{id}/decision；评测跑批=/api/dev/eval/* 任意方法
     *  （跑批会驱动整图、真实 LLM 下消耗配额，GET 触发同样要拦）；其余放行 */
    private boolean isProtected(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/dev/eval")) {
            return true;
        }
        return "POST".equalsIgnoreCase(request.getMethod()) && uri.endsWith("/decision");
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (expected.length() != actual.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ actual.charAt(i);
        }
        return diff == 0;
    }
}
