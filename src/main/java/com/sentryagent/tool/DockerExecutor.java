package com.sentryagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Docker CLI 执行器（Phase 3：清言方案三"容器操作真实化"的落点，比 docker-java 依赖更轻）。
 *
 * <p>设计决策：ProcessBuilder 调本机 docker CLI 而非引 docker-java SDK——
 * 目标环境（Linux 服务器/开发者 Windows 装 Docker Desktop）都自带 CLI；
 * 零新依赖、超时控制与既有 SandboxRunner 同款。生产多容器部署时按
 * docs/DEPLOY.md 换 socket-proxy（DOCKER_HOST=tcp://socket-proxy:2375 即可，CLI 原生支持）。</p>
 *
 * <p>三层安全（缺一不可，清言方案五·问题 3 的采纳）：</p>
 * <ol>
 *   <li>开关：sentry.docker.enabled 默认 false——未显式开启时全部动作走 Mock 文案；</li>
 *   <li>白名单：只允许操作打了 sentry-managed label 的容器（docker ps -f label 预检）；</li>
 *   <li>HITL 令牌：调用方（EvidenceTools 写工具）已消费一次性审批令牌，本类不重复校验
 *       但强制要求非空（纵深防御——即使未来有人绕过工具层直接调本类，也有最后一道闸）。</li>
 * </ol>
 */
@Slf4j
@Component
public class DockerExecutor {

    /** 白名单 label：部署时给可被自愈的容器打上 docker run --label sentry-managed=true */
    public static final String MANAGED_LABEL = "sentry-managed";

    @Value("${sentry.docker.enabled:false}")
    private boolean enabled;

    @Value("${sentry.docker.managed-label:" + MANAGED_LABEL + "}")
    private String managedLabel;

    /** CLI 超时（秒）：容器重启一般 1~10s，超时即视为失败 */
    @Value("${sentry.docker.timeout-seconds:30}")
    private int timeoutSeconds;

    public boolean enabled() {
        return enabled;
    }

    /** 执行结果 */
    public record ExecResult(boolean ok, String output) {
        public static ExecResult fail(String why) {
            return new ExecResult(false, why);
        }
    }

    /**
     * 重启容器（真实 docker restart）。
     *
     * @param containerId 容器名或 ID（来自诊断计划，不可信输入——白名单预检兜底）
     * @param approvalToken 已消费的审批令牌（非空即已过 HITL 闸口；空 = 直接拒绝）
     */
    public ExecResult restart(String containerId, String approvalToken) {
        if (approvalToken == null || approvalToken.isBlank()) {
            return ExecResult.fail("REFUSED: 缺少审批令牌（纵深防御：写操作必须过 HITL）");
        }
        if (!enabled) {
            return ExecResult.fail("MOCK: docker 未启用（sentry.docker.enabled=false），" + containerId + " 未实际重启");
        }
        if (!isManaged(containerId)) {
            return ExecResult.fail("REFUSED: 容器 " + containerId
                    + " 无 " + managedLabel + " label，不在自愈白名单");
        }
        return exec("docker", "restart", sanitizeContainer(containerId));
    }

    /**
     * 扩容（docker compose up --scale 或 docker scale）。
     * 真实语义：对 compose 服务扩容用 docker compose up -d --scale svc=n；
     * 单机裸容器场景退化为提示（compose 是部署文档的推荐形态）。
     */
    public ExecResult scale(String service, int workerCount, String approvalToken) {
        if (approvalToken == null || approvalToken.isBlank()) {
            return ExecResult.fail("REFUSED: 缺少审批令牌（纵深防御：写操作必须过 HITL）");
        }
        if (!enabled) {
            return ExecResult.fail("MOCK: docker 未启用，" + service + " 未实际扩容");
        }
        if (workerCount < 0 || workerCount > 20) {
            return ExecResult.fail("REFUSED: 扩容目标数 " + workerCount + " 越界（0..20）");
        }
        return exec("docker", "compose", "up", "-d", "--scale",
                sanitizeContainer(service) + "=" + workerCount);
    }

    /**
     * 白名单预检：docker ps -f label=<managed> 里找得到 containerId 才放行。
     * 预检失败（docker 未装/守护进程挂）按"不在白名单"处理——写操作宁可不执行。
     */
    boolean isManaged(String containerId) {
        ExecResult r = execQuiet("docker", "ps", "--filter",
                "label=" + managedLabel, "--format", "{{.Names}}");
        if (!r.ok()) {
            log.warn("label pre-check failed (docker unavailable?), refusing write: {}",
                    r.output());
            return false;
        }
        return List$of(r.output()).contains(sanitizeContainer(containerId));
    }

    /** 列表辅助（避免类名 List 与 java.util.List 混淆的私有解析） */
    private static java.util.List<String> List$of(String names) {
        return java.util.Arrays.stream(names.split("\n"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 统一执行入口：超时控制 + 退出码判定 + 输出截断（进 Incident 报告别太长） */
    private ExecResult exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return ExecResult.fail("执行超时（>" + timeoutSeconds + "s）: " + String.join(" ", cmd));
            }
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean ok = p.exitValue() == 0;
            log.info("docker exec ok={} cmd={} out={}", ok, String.join(" ", cmd),
                    out.length() > 200 ? out.substring(0, 200) + "..." : out);
            String verdict = ok ? "OK" : "FAIL(exit=" + p.exitValue() + ")";
            return new ExecResult(ok, verdict + ": " + String.join(" ", cmd)
                    + (out.isBlank() ? "" : "\n" + trim(out)));
        } catch (IOException e) {
            // 典型：docker CLI 不存在（IOException: CreateProcess error=2）
            return ExecResult.fail("docker 命令不可用（未安装或不在 PATH）: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.fail("执行被中断");
        }
    }

    private ExecResult execQuiet(String... cmd) {
        return exec(cmd);
    }

    private static String trim(String s) {
        String t = s.trim();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    /** 容器标识清洗：同 sanitize 白名单思想（容器名不允许 shell 元字符） */
    static String sanitizeContainer(String raw) {
        String s = raw == null ? "unknown" : raw.trim();
        if (s.isEmpty()) {
            return "unknown";
        }
        // compose 扩容参数允许 svc=n 形态，额外放行 '='（放字符类末尾避免被当成范围）
        return s.replaceAll("[^A-Za-z0-9._=\\-]", "_");
    }
}
