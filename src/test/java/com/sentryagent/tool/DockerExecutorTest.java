package com.sentryagent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DockerExecutor 三层安全单测（Phase 3）：
 * 开关 / sentry-managed 白名单 / 令牌纵深防御 / 参数清洗 / 越界防护。
 * 真实 docker CLI 不参与（CI 无 docker 也必须全绿）——enabled=false 路径即可覆盖全部分支。
 */
class DockerExecutorTest {

    private DockerExecutor executor(boolean enabled) {
        DockerExecutor d = new DockerExecutor();
        ReflectionTestUtils.setField(d, "enabled", enabled);
        ReflectionTestUtils.setField(d, "managedLabel", "sentry-managed");
        ReflectionTestUtils.setField(d, "timeoutSeconds", 5);
        return d;
    }

    @Test
    @DisplayName("开关关闭（默认）：restart 返回 MOCK 文案且如实声明未执行")
    void disabledRestartIsMockHonest() {
        DockerExecutor d = executor(false);
        DockerExecutor.ExecResult r = d.restart("judge-worker-1", "tok-1");
        assertFalse(r.ok(), "Mock 未执行不能算 ok——诚实行数是本类的立场");
        assertTrue(r.output().startsWith("MOCK:"));
        assertTrue(r.output().contains("未实际重启"));
    }

    @Test
    @DisplayName("纵深防御：开关关闭也必须先验令牌——空令牌直接 REFUSED")
    void tokenGuardComesFirst() {
        DockerExecutor d = executor(false);
        DockerExecutor.ExecResult r = d.restart("judge-worker-1", "");
        assertTrue(r.output().startsWith("REFUSED:"), "令牌缺失优先于 Mock 分支");
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("开关开启+本机无 docker：白名单预检失败 → 宁可不执行（fail-closed）")
    void enabledWithoutDockerDaemonFailsClosed() {
        DockerExecutor d = executor(true);
        // isManaged 会执行 docker ps——CI 无 docker 时预检失败，写操作被拒绝而非放行
        DockerExecutor.ExecResult r = d.restart("judge-worker-1", "tok-1");
        assertFalse(r.ok());
        assertTrue(r.output().startsWith("REFUSED:") || r.output().startsWith("docker 命令不可用"),
                "fail-closed 二选一，绝不放行: " + r.output());
    }

    @Test
    @DisplayName("scale 越界防护：workerCount 超出 0..20 拒绝（开关开启下走到数值校验）")
    void scaleRangeGuard() {
        DockerExecutor d = executor(true);
        DockerExecutor.ExecResult r = d.scale("judge-service", 99, "tok-1");
        assertTrue(r.output().contains("越界"));
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("容器标识清洗：shell 元字符（;/空格/$()）被替换，- 保留")
    void containerIdSanitized() {
        // 分号与空格是注入关键字符，全部替换为 _；- 合法保留
        assertEquals("judge_rm_-rf_worker-1", DockerExecutor.sanitizeContainer("judge;rm -rf worker-1"));
        assertEquals("svc__x_", DockerExecutor.sanitizeContainer("svc$(x)"));
        assertEquals("a=b", DockerExecutor.sanitizeContainer("a=b"), "compose scale 允许 =");
        assertEquals("unknown", DockerExecutor.sanitizeContainer("  "));
    }
}
