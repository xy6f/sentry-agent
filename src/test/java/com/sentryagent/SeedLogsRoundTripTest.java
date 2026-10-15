package com.sentryagent;

import com.sentryagent.tool.EvidenceTools;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * seed-logs -> fetchServiceLogs 读写闭环验收（真实文件链路，不经 mock 网关）：
 * dev 接口写剧本日志进 sentry.logs-dir，读工具 tail 回同一段内容。
 * 日志目录指到 target/ 下，测试跑完自清洁，不污染工作区 ./logs。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sentry.logs-dir=./target/test-seed-logs")
class SeedLogsRoundTripTest {

    private static final Path SEED_DIR = Path.of("target/test-seed-logs");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EvidenceTools tools;

    /** 起手清空夹具目录：保证"文件不存在"断言面对的是干净环境 */
    @BeforeAll
    static void cleanSeedDir() throws IOException {
        if (Files.exists(SEED_DIR)) {
            try (Stream<Path> walk = Files.walk(SEED_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 夹具目录删除失败不影响测试语义，忽略
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("seed-logs 写入后 fetchServiceLogs 能 tail 到 timeout killed 关键行")
    void seedThenFetch() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/dev/seed-logs")
                        .contentType("application/json")
                        .content("{\"service\":\"judge-service\"}"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.lines").isNumber())
                .andExpect(MockMvcResultMatchers.jsonPath("$.fetchServiceLogsPreview")
                        .value(org.hamcrest.Matchers.containsString("timeout killed")));

        // 读侧真实文件路径：关键字过滤命中剧本关键行
        String logs = tools.fetchServiceLogs("judge-service", "timeout killed");
        assertTrue(logs.startsWith("logs[judge-service]"), logs);
        assertTrue(logs.contains("docker timeout killed container judge-worker-1"), logs);
        // 全 tail（keyword 传 null）也能看到队列爬升行
        assertTrue(tools.fetchServiceLogs("judge-service", null).contains("queueDepth=320"));
    }

    @Test
    @DisplayName("无日志/过滤未命中/路径穿越三类降级文案都以'无日志'或安全清洗呈现")
    void gracefulDegrade() {
        String missing = tools.fetchServiceLogs("ghost-service", null);
        assertTrue(missing.startsWith("logs[ghost-service] 无日志"), missing);

        String noMatch = tools.fetchServiceLogs("judge-service", "NoSuchKeyword12345");
        assertTrue(noMatch.contains("无日志"), noMatch);

        // 穿越参数被清洗：斜杠换下划线后 ".." 只是文件名的一部分（.._evil.log），
        // 不再构成任何目录元素——解析后的路径始终落在日志目录内
        String traversal = tools.fetchServiceLogs("../evil", null);
        assertTrue(traversal.startsWith("logs[.._evil]"), traversal);
        assertTrue(!traversal.contains("../") && !traversal.contains("..\\"),
                "回显不得含可生效的穿越序列");
    }
}
