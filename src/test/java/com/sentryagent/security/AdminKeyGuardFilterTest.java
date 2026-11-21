package com.sentryagent.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AdminKeyGuardFilter 单测（第四批 4.1）：开关语义、路径范围、方法范围、
 * 缺失/错误/正确三种 header 形态 + 时序比较函数边界。
 */
class AdminKeyGuardFilterTest {

    /** 放行哨兵：chain 被调用即 true */
    private final class SentinelChain implements FilterChain {
        final AtomicBoolean reached = new AtomicBoolean();
        @Override
        public void doFilter(jakarta.servlet.ServletRequest req,
                             jakarta.servlet.ServletResponse res) {
            reached.set(true);
        }
    }

    private AdminKeyGuardFilter filter(String key) {
        return new AdminKeyGuardFilter(key);
    }

    private MockHttpServletRequest req(String method, String uri, String header) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        if (header != null) {
            r.addHeader("X-Admin-Key", header);
        }
        return r;
    }

    @Test
    @DisplayName("空 key（默认）：决策/跑批全部放行，回归与本地演示零影响")
    void emptyKeyDisablesGuard() throws Exception {
        AdminKeyGuardFilter f = filter("");
        for (String[] m : new String[][]{{"POST", "/api/incidents/1/decision"},
                {"GET", "/api/dev/eval/scenarios"}}) {
            SentinelChain chain = new SentinelChain();
            f.doFilterInternal(req(m[0], m[1], null), new MockHttpServletResponse(), chain);
            assertTrue(chain.reached.get(), m[0] + " " + m[1]);
        }
    }

    @Test
    @DisplayName("key 配置后：决策 POST 无头/错头 401 且不进链；正确头放行")
    void decisionGuarded() throws Exception {
        AdminKeyGuardFilter f = filter("s3cret");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        SentinelChain c1 = new SentinelChain();
        f.doFilterInternal(req("POST", "/api/incidents/7/decision", null), resp, c1);
        assertFalse(c1.reached.get());
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("FORBIDDEN_ADMIN_KEY"));

        SentinelChain c2 = new SentinelChain();
        f.doFilterInternal(req("POST", "/api/incidents/7/decision", "wrong"),
                new MockHttpServletResponse(), c2);
        assertFalse(c2.reached.get());

        SentinelChain c3 = new SentinelChain();
        f.doFilterInternal(req("POST", "/api/incidents/7/decision", "s3cret"),
                new MockHttpServletResponse(), c3);
        assertTrue(c3.reached.get());
    }

    @Test
    @DisplayName("评测跑批 GET 也受控（驱动整图耗配额）；普通查询面放行")
    void evalGuardedQueryOpen() throws Exception {
        AdminKeyGuardFilter f = filter("s3cret");
        SentinelChain eval = new SentinelChain();
        f.doFilterInternal(req("GET", "/api/dev/eval/scenarios", null),
                new MockHttpServletResponse(), eval);
        assertFalse(eval.reached.get(), "跑批无钥必须拦");

        SentinelChain q = new SentinelChain();
        f.doFilterInternal(req("GET", "/api/incidents/7", "s3cret".isEmpty() ? null : null),
                new MockHttpServletResponse(), q);
        assertTrue(q.reached.get(), "查询面放行");

        SentinelChain list = new SentinelChain();
        f.doFilterInternal(req("GET", "/api/incidents", null),
                new MockHttpServletResponse(), list);
        assertTrue(list.reached.get());
    }

    @Test
    @DisplayName("header 带空格容错（trim 对齐令牌消费语义）")
    void headerTrimmed() throws Exception {
        AdminKeyGuardFilter f = filter("s3cret");
        SentinelChain c = new SentinelChain();
        f.doFilterInternal(req("POST", "/api/incidents/1/decision", "  s3cret  "),
                new MockHttpServletResponse(), c);
        assertTrue(c.reached.get());
    }

    @Test
    @DisplayName("constantTimeEquals：等/不等/长度差/空串")
    void constantTimeCompare() {
        assertTrue(AdminKeyGuardFilter.constantTimeEquals("abc", "abc"));
        assertFalse(AdminKeyGuardFilter.constantTimeEquals("abc", "abd"));
        assertFalse(AdminKeyGuardFilter.constantTimeEquals("abc", "abcd"));
        assertTrue(AdminKeyGuardFilter.constantTimeEquals("", ""));
    }
}
