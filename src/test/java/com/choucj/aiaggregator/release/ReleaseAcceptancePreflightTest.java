package com.choucj.aiaggregator.release;

import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import com.choucj.aiaggregator.common.config.RedisClusterProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 10.15: release acceptance 前置检查 (preflight) — 前置条件 gate。
 *
 * <p>与既有 external 测试的 assume-skip 守卫相反: preflight 把环境缺失从 skip 翻转为
 * <b>FAIL</b>。微信凭据、Redis Cluster 三节点、PHOTO fixture 任一缺失即构建失败,
 * 且<b>缺失项一次性列全</b> (避免逐个试错), 后续必需测试不再执行
 * (本测试绑定在 release-acceptance profile 的 process-test-classes 阶段, 先于必需套件)。
 *
 * <p>检查项 (与 pom.xml release-acceptance profile 必需集对应):
 * <ol>
 *   <li>微信凭据: {@code wx.mp.app-id/secret} 已解析为真实值 (非空白/占位符)</li>
 *   <li>Redis Cluster 三节点: 按 {@code redis.cluster.nodes} 逐节点 PING (每节点独立连接,
 *       任一节点不可达即缺失 — 不以单节点连通冒充三节点健康)</li>
 *   <li>PHOTO fixture: {@code classpath:fixtures/release-acceptance/photo.png} 存在且非空</li>
 * </ol>
 *
 * <p>证据与日志脱敏: 凭据只判空/判占位, 不打印任何真实 app-id/secret。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 与 e2e external 测试一致: 开启项目层微信装配, 保证 WxMpProperties 可注入
        "wechat.mp.enabled=true",
        "schedule.run-on-startup=false"
})
@Tag("external")
class ReleaseAcceptancePreflightTest {

    /** 已知的占位凭据值 (test application.yml / application.example.yml 缺省值), 视为缺失。 */
    private static final Set<String> PLACEHOLDER_CREDENTIALS = Set.of(
            "dev-placeholder", "test-only-app-id", "test-only-secret");

    private static final String PHOTO_FIXTURE_CLASSPATH =
            "/fixtures/release-acceptance/photo.png";

    /** PNG 文件魔数 (8 字节签名), 用于提前拦截损坏 fixture。 */
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * 每次门禁运行从干净状态开始: 清零上一轮遗留的待人工清理记录,
     * 避免草稿已被人工删除后 CONCERN 在后续每次构建中永久重放 (告警疲劳)。
     * 本次运行期间写入的记录不受影响, 仍会在 verify 守门器处输出 CONCERN。
     */
    @BeforeAll
    static void resetPendingCleanupLedger() {
        try {
            Files.deleteIfExists(Path.of("target/release-acceptance/pending-cleanup.txt"));
        } catch (Exception e) {
            System.out.printf("[story-10.15] 待清理清单清零失败(不影响前置检查): err=%s%n",
                    e.getClass().getSimpleName());
        }
    }

    @Autowired
    private WxMpProperties wxMpProperties;

    @Autowired
    private RedisClusterProperties redisClusterProperties;

    @Test
    @Timeout(120)
    void shouldFailWithFullMissingListWhenAnyPrerequisiteIsAbsent() {
        List<String> missing = new ArrayList<>();

        checkWeChatCredentials(missing);
        checkRedisClusterNodes(missing);
        checkPhotoFixture(missing);

        // 缺失即 FAIL (非 skip): 缺失项一次性列全, 给出可操作的修复提示
        assertThat(missing)
                .as("release acceptance 前置条件缺失 — 补齐以下环境后重跑 "
                        + "./mvnw verify -P release-acceptance")
                .isEmpty();
    }

    /** 检查 1: 微信凭据已解析为真实值。 */
    private void checkWeChatCredentials(List<String> missing) {
        String appId = wxMpProperties.getAppId();
        String secret = wxMpProperties.getSecret();
        if (!isRealCredential(appId)) {
            // 注意 Spring 宽松绑定: app-id 含连字符, 环境变量名须为 WX_MP_APPID (无下划线分隔的连字符段);
            // secret 无连字符, 环境变量名为 WX_MP_SECRET
            missing.add("微信凭据缺失: wx.mp.app-id 未配置为真实值 "
                    + "(提供环境变量 WX_MP_APPID / WX_MP_SECRET 或等效配置)");
        }
        if (!isRealCredential(secret)) {
            missing.add("微信凭据缺失: wx.mp.secret 未配置为真实值 "
                    + "(提供环境变量 WX_MP_APPID / WX_MP_SECRET 或等效配置)");
        }
    }

    /** 检查 2: Redis Cluster 三节点逐个 PING。 */
    private void checkRedisClusterNodes(List<String> missing) {
        List<RedisClusterProperties.Node> nodes = redisClusterProperties.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            missing.add("Redis Cluster 缺失: redis.cluster.nodes 未配置");
            return;
        }
        // 门禁语义是「三节点 Cluster 健康」, 配置不足三节点即缺失, 不以单/双节点连通冒充
        if (nodes.size() < 3) {
            missing.add("Redis Cluster 节点数不足: redis.cluster.nodes 配置了 " + nodes.size()
                    + " 个, 门禁要求三节点");
            return;
        }
        for (RedisClusterProperties.Node node : nodes) {
            String target = node.getHost() + ":" + node.getPort();
            try {
                String pong = pingNode(node);
                if (!"PONG".equalsIgnoreCase(pong)) {
                    missing.add("Redis Cluster 节点不可达: " + target
                            + " (PING 返回 " + pong + ")");
                }
            } catch (Exception e) {
                // 脱敏: 只记节点地址与异常类名, 不打完整异常链 (可能含内部拓扑细节)
                missing.add("Redis Cluster 节点不可达: " + target
                        + " (" + e.getClass().getSimpleName() + ")");
            }
        }
    }

    /** 单节点独立 PING — 任一节点不可达都计入缺失清单。 */
    private String pingNode(RedisClusterProperties.Node node) {
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(node.getHost())
                .withPort(node.getPort())
                .withTimeout(Duration.ofSeconds(3));
        if (StringUtils.hasText(redisClusterProperties.getPassword())) {
            uriBuilder.withPassword(redisClusterProperties.getPassword());
        }
        RedisClient client = RedisClient.create(uriBuilder.build());
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            return connection.sync().ping();
        } finally {
            client.shutdown();
        }
    }

    /** 检查 3: PHOTO fixture 存在且非空 (release 套件的稳定输入, 不依赖本地偶然样例文件)。 */
    private void checkPhotoFixture(List<String> missing) {
        var fixture = getClass().getResource(PHOTO_FIXTURE_CLASSPATH);
        if (fixture == null) {
            missing.add("PHOTO fixture 缺失: classpath:" + PHOTO_FIXTURE_CLASSPATH
                    + " 不存在 (src/test/resources/fixtures/release-acceptance/photo.png)");
            return;
        }
        try (var stream = fixture.openStream()) {
            byte[] bytes = stream.readAllBytes();
            if (bytes.length == 0) {
                missing.add("PHOTO fixture 为空文件: classpath:" + PHOTO_FIXTURE_CLASSPATH);
                return;
            }
            // PNG 魔数校验: 损坏 fixture 应在 preflight 即失败, 而非烧掉昂贵的真实微信调用后才暴露
            if (bytes.length < PNG_SIGNATURE.length
                    || !java.util.Arrays.equals(java.util.Arrays.copyOf(bytes, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
                missing.add("PHOTO fixture 非有效 PNG (魔数不符): classpath:" + PHOTO_FIXTURE_CLASSPATH);
            }
        } catch (Exception e) {
            missing.add("PHOTO fixture 不可读: classpath:" + PHOTO_FIXTURE_CLASSPATH
                    + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** 凭据真实性: 非空白且不命中已知占位值。 */
    private static boolean isRealCredential(String value) {
        return StringUtils.hasText(value) && !PLACEHOLDER_CREDENTIALS.contains(value);
    }
}
