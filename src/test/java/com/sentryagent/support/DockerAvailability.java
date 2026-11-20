package com.sentryagent.support;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker / 本机 MySQL 可用性探测工具（CI 无 DB 化改造的配套，不足清单第 8 项）。
 *
 * <p>目标：把"测试数据源走哪条路"变成可缓存、可解释的判定，供
 * {@code RequiresDockerCondition}（JUnit 执行条件）消费。判定语义：</p>
 *
 * <ol>
 *   <li>Docker 引擎可用 → Testcontainers 拉起一次性 MySQL（容器路径）；</li>
 *   <li>Docker 不可用而本机 MySQL 可达 → 回退 localhost 数据源（本地路径）；</li>
 *   <li>两者皆不可达 → DB 依赖用例整类 SKIP（跳过不等于失败）。</li>
 * </ol>
 *
 * <p>Docker 探测：执行 {@code docker info} 并看退出码 + 输出里有无 Server Version
 * （Docker Desktop "进程在但引擎未启动"等半就绪状态只有它一次问清）。MySQL 探测：
 * 对 application-test.yml 里回退 url 的 host:port 做一次 TCP 连接（解析失败按
 * localhost:3306 兜底）——探测目标与测试实际要用的回退端口天然一致，模拟
 * "MySQL 不可用"只需把 yml 指向死端口，无需真的停服务。</p>
 *
 * <p>两项结果均按 JVM 生命周期缓存（套件运行期间引擎状态不会翻转，重复探测
 * 只拖慢启动）。后门环境变量 {@code SENTRY_TEST_FORCE_NO_DOCKER=true} 强制
 * 按"无 Docker"处理，用于本机模拟验收场景。</p>
 */
public final class DockerAvailability {

    /** 强制"无 Docker"的环境变量名（本机模拟验收场景 b/c 的开关） */
    public static final String FORCE_NO_DOCKER_ENV = "SENTRY_TEST_FORCE_NO_DOCKER";

    /** 探测 application-test.yml 回退数据源的 host:port（user/pass 无关连通性） */
    private static final Pattern URL_PATTERN =
            Pattern.compile("jdbc:mysql://([^:/\":]+):(\\d+)");

    /** classpath 里测试回退配置的位置（maven test-compile 后必然存在于 test-classes） */
    private static final String FALLBACK_RESOURCE = "application-test.yml";

    /** 缓存：Docker 探测结果 */
    private static volatile Boolean dockerCached;

    /** 缓存：本机 MySQL 探测结果 */
    private static volatile Boolean mysqlCached;

    private DockerAvailability() {
        // 纯工具类，禁止实例化
    }

    /**
     * Docker 引擎当前是否可用（进程内缓存，只探测一次）。
     *
     * @return true = docker info 正常返回 Server Version，可安全使用 Testcontainers
     */
    public static boolean isDockerAvailable() {
        Boolean c = dockerCached;
        if (c == null) {
            synchronized (DockerAvailability.class) {
                c = dockerCached;
                if (c == null) {
                    c = detectDocker();
                    dockerCached = c;
                }
            }
        }
        return c;
    }

    /**
     * 本机回退 MySQL 是否可达（进程内缓存，只探测一次）。
     *
     * @return true = application-test.yml 回退 url 的 host:port TCP 可连通
     */
    public static boolean isLocalMysqlReachable() {
        Boolean c = mysqlCached;
        if (c == null) {
            synchronized (DockerAvailability.class) {
                c = mysqlCached;
                if (c == null) {
                    c = detectLocalMysql();
                    mysqlCached = c;
                }
            }
        }
        return c;
    }

    /** 人类可读的三级择路说明（打进 SKIP/执行原因，方便 CI 上定位） */
    public static String describe() {
        if (Boolean.parseBoolean(System.getenv(FORCE_NO_DOCKER_ENV))) {
            return "环境变量 " + FORCE_NO_DOCKER_ENV + "=true，强制按无 Docker 处理（模拟验收）";
        }
        if (isDockerAvailable()) {
            return "docker info 正常（Server Version 在场），走 Testcontainers 容器路径";
        }
        return "docker info 探测失败：CLI 不存在或守护进程未启动";
    }

    /**
     * 实际探测 Docker：docker info 退出码 0 且输出含 Server Version 才算可用。
     * 任何异常（命令不存在、IO 错误、超时）一律按"不可用"收敛——探测器自己
     * 绝不能成为测试失败的来源。
     */
    private static boolean detectDocker() {
        // 验收后门：强制按无 Docker 处理
        if (Boolean.parseBoolean(System.getenv(FORCE_NO_DOCKER_ENV))) {
            return false;
        }
        String cli = resolveDockerCli();
        if (cli == null) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(cli, "info")
                    .redirectErrorStream(true)
                    .start();
            // 读干输出流防止缓冲区填满阻塞；内容参与判定（Server Version = 引擎真活着）
            byte[] out = p.getInputStream().readAllBytes();
            boolean done = p.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0
                    && new String(out, StandardCharsets.UTF_8).contains("Server Version");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 实际探测本机 MySQL：解析 application-test.yml 的回退 url 取 host:port，
     * 再做一次短超时 TCP 连接。TCP 层可达即认为回退路径活着（密码对不对是
     * 业务断言的事，不该由探测器越权）。
     */
    private static boolean detectLocalMysql() {
        String host = "localhost";
        int port = 3306;
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(FALLBACK_RESOURCE)) {
            if (in != null) {
                String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = URL_PATTERN.matcher(yml);
                if (m.find()) {
                    host = m.group(1);
                    port = Integer.parseInt(m.group(2));
                }
            }
        } catch (IOException e) {
            // 读不到配置就按缺省 localhost:3306 兜底，不影响判定
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1500);
            return s.isConnected();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 定位 docker CLI：优先扫描 PATH，找不到再补常见安装路径
     * （Docker Desktop for Windows 默认 C:\Program Files\Docker\Docker\resources\bin）。
     *
     * @return 可执行文件的绝对路径；定位不到返回 null
     */
    private static String resolveDockerCli() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                java.io.File exe = new java.io.File(dir, "docker.exe");
                if (exe.isFile()) {
                    return exe.getAbsolutePath();
                }
                java.io.File bare = new java.io.File(dir, "docker");
                if (bare.isFile()) {
                    return bare.getAbsolutePath();
                }
            }
        }
        // Windows Docker Desktop 固定安装位兜底
        java.io.File winDefault =
                new java.io.File("C:/Program Files/Docker/Docker/resources/bin/docker.exe");
        return winDefault.isFile() ? winDefault.getAbsolutePath() : null;
    }
}
