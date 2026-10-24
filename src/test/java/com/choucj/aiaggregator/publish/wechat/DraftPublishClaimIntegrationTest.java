package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.util.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Story 10.16 集成测试 — 真 Redis Cluster 上验证草稿发布原子认领与对账收据键族
 * (与 {@code TaskQueueRecoveryIntegrationTest} 相同的 external 双轨与跳过策略):
 * <ol>
 *   <li>并发双实例 claim 竞争: 同一 articleId 恰一个 ACQUIRED, 其余 SKIP_HELD(跨实例原子性证据)</li>
 *   <li>租约 TTL 过期后可再认领(崩溃残留自愈)</li>
 *   <li>对账收据写入后 claim 命中 SKIP_EXISTING_RECEIPT; 收据读取/清除与键 TYPE 一致</li>
 *   <li>非持有者不释放他人租约; 租约丢失时拒绝写入陈旧收据</li>
 * </ol>
 *
 * <p><b>跳过策略:</b> 本地无 Redis 时 assume-skip, 不阻塞默认回归;
 * {@code -Pexternal-tests} 开发 smoke 入口语义不变。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("external")
class DraftPublishClaimIntegrationTest {

    private static final String ARTICLE_ID = "tw-claim-it-1";

    @Autowired
    private DraftPublishClaimService claimService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeAll
    void requireRedisOnline() {
        boolean reachable;
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            reachable = "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "本地 Redis 不可用,跳过 Story 10.16 集成测试");
    }

    @BeforeEach
    @AfterEach
    void cleanupClaimKeys() {
        redisTemplate.delete(RedisKeys.taskDraftClaim(ARTICLE_ID));
        redisTemplate.delete(RedisKeys.taskDraftReconcile(ARTICLE_ID));
    }

    @Test
    void shouldGrantLeaseToExactlyOneClaimerUnderConcurrentContention() throws Exception {
        int claimerCount = 8;
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger held = new AtomicInteger();
        AtomicInteger receiptHit = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(claimerCount);
        try {
            for (int i = 0; i < claimerCount; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    switch (claimService.acquire(ARTICLE_ID)) {
                        case ACQUIRED -> acquired.incrementAndGet();
                        case SKIP_HELD -> held.incrementAndGet();
                        case SKIP_EXISTING_RECEIPT -> receiptHit.incrementAndGet();
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                fail("并发认领未在超时内完成");
            }
        }

        assertThat(acquired.get()).as("恰一个实例认领成功(跨实例原子性)").isEqualTo(1);
        assertThat(held.get() + receiptHit.get()).as("其余实例全部被拒且不触达远端")
                .isEqualTo(claimerCount - 1);
        assertThat(receiptHit.get()).as("无收据时不得出现收据命中误判").isZero();
    }

    @Test
    void shouldReacquireLeaseAfterTtlExpiry() {
        assertThat(claimService.acquire(ARTICLE_ID, Duration.ofMillis(80)))
                .isEqualTo(DraftPublishClaimService.ClaimOutcome.ACQUIRED);
        assertThat(claimService.acquire(ARTICLE_ID, Duration.ofSeconds(10)))
                .as("租约未过期时不得重复认领")
                .isEqualTo(DraftPublishClaimService.ClaimOutcome.SKIP_HELD);

        assertThatThrownBy(() -> claimService.acquire(ARTICLE_ID, Duration.ofMillis(-1)))
                .as("非正 TTL 必须拒绝(防误配永久租约)")
                .isInstanceOf(com.choucj.aiaggregator.common.exception.NonRetryableException.class);

        try {
            TimeUnit.MILLISECONDS.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(claimService.acquire(ARTICLE_ID, Duration.ofSeconds(10)))
                .as("租约 TTL 过期后键自动可再获取(崩溃残留自愈)")
                .isEqualTo(DraftPublishClaimService.ClaimOutcome.ACQUIRED);
    }

    @Test
    void shouldRecordReconcileReceiptAndHitSkipExistingOnNextClaim() {
        assertThat(claimService.acquire(ARTICLE_ID))
                .isEqualTo(DraftPublishClaimService.ClaimOutcome.ACQUIRED);

        claimService.recordReconcileReceipt(ARTICLE_ID, "media-integration");

        // 同一 Lua 原子释放租约并留收据; 收据日志脱敏由单元测试覆盖
        assertThat(redisTemplate.hasKey(RedisKeys.taskDraftClaim(ARTICLE_ID)))
                .as("记录收据时应原子释放租约")
                .isFalse();
        Optional<DraftPublishClaimService.ReconcileReceipt> receipt =
                claimService.readReconcileReceipt(ARTICLE_ID);
        assertThat(receipt).as("对账收据可读且 mediaId 完整留存")
                .hasValueSatisfying(r -> {
                    assertThat(r.mediaId()).isEqualTo("media-integration");
                    assertThat(r.articleId()).isEqualTo(ARTICLE_ID);
                    assertThat(r.recordedAt()).isNotBlank();
                });

        assertThat(claimService.acquire(ARTICLE_ID))
                .as("收据存在时 claim 命中幂等跳过, 绝不再触达远端")
                .isEqualTo(DraftPublishClaimService.ClaimOutcome.SKIP_EXISTING_RECEIPT);

        claimService.clearReconcileReceipt(ARTICLE_ID);
        assertThat(claimService.readReconcileReceipt(ARTICLE_ID)).isEmpty();
        assertThat(claimService.acquire(ARTICLE_ID))
                .as("对账补全清除收据后恢复正常认领")
                .isEqualTo(DraftPublishClaimService.ClaimOutcome.ACQUIRED);
    }

    @Test
    void shouldNotReleaseLeaseHeldByAnotherInstance() {
        redisTemplate.opsForValue().set(RedisKeys.taskDraftClaim(ARTICLE_ID), "other-instance");

        assertThat(claimService.release(ARTICLE_ID))
                .as("非持有者不得释放他人租约")
                .isFalse();
        assertThat(redisTemplate.opsForValue().get(RedisKeys.taskDraftClaim(ARTICLE_ID)))
                .isEqualTo("other-instance");
    }

    @Test
    void shouldRefuseStaleReceiptWhenLeaseLost() {
        redisTemplate.opsForValue().set(RedisKeys.taskDraftClaim(ARTICLE_ID), "other-instance");

        // 租约已被新实例持有: 旧持有者不得写入陈旧收据覆盖新持有者的判断窗口
        assertThatThrownBy(() -> claimService.recordReconcileReceipt(ARTICLE_ID, "media-stale"))
                .isInstanceOf(RetryableException.class);
        assertThat(claimService.readReconcileReceipt(ARTICLE_ID)).isEmpty();
        assertThat(redisTemplate.opsForValue().get(RedisKeys.taskDraftClaim(ARTICLE_ID)))
                .isEqualTo("other-instance");
    }

    @Test
    void shouldClaimDistinctArticleIdsIndependently() {
        List<String> articles = List.of("tw-claim-it-a", "tw-claim-it-b");
        try {
            for (String articleId : articles) {
                assertThat(claimService.acquire(articleId))
                        .isEqualTo(DraftPublishClaimService.ClaimOutcome.ACQUIRED);
            }
            assertThat(claimService.acquire(articles.get(0)))
                    .isEqualTo(DraftPublishClaimService.ClaimOutcome.SKIP_HELD);
            assertThat(claimService.acquire(articles.get(1)))
                    .isEqualTo(DraftPublishClaimService.ClaimOutcome.SKIP_HELD);
        } finally {
            articles.forEach(id -> {
                redisTemplate.delete(RedisKeys.taskDraftClaim(id));
                redisTemplate.delete(RedisKeys.taskDraftReconcile(id));
            });
        }
    }

    @Test
    void shouldAllowAcquiredHolderToReleaseItsOwnLease() {
        assertThat(claimService.acquire(ARTICLE_ID))
                .isEqualTo(DraftPublishClaimService.ClaimOutcome.ACQUIRED);
        assertThat(claimService.release(ARTICLE_ID)).isTrue();
        assertThat(redisTemplate.hasKey(RedisKeys.taskDraftClaim(ARTICLE_ID))).isFalse();
        assertThat(claimService.release(ARTICLE_ID)).as("重复释放幂等 no-op").isFalse();
        assertThat(claimService.acquire(ARTICLE_ID))
                .isEqualTo(DraftPublishClaimService.ClaimOutcome.ACQUIRED);
    }

    @Test
    void shouldRejectClaimWhenReconcileKeyHasWrongType() {
        redisTemplate.opsForValue().set(RedisKeys.taskDraftReconcile(ARTICLE_ID), "junk");

        assertThatThrownBy(() -> claimService.acquire(ARTICLE_ID))
                .as("对账键 TYPE 损坏必须 fail-closed 拒绝(不触达远端)")
                .isInstanceOf(com.choucj.aiaggregator.common.exception.NonRetryableException.class)
                .hasMessageContaining("TYPE");
        assertThat(redisTemplate.hasKey(RedisKeys.taskDraftClaim(ARTICLE_ID)))
                .as("拒绝路径不得写入租约(零写入)")
                .isFalse();
    }

    @Test
    void shouldRejectClaimWhenClaimKeyHasWrongType() {
        // 认领键 TYPE 损坏(Redis Hash 冒充租约 String): fail-closed 拒绝且零写入
        redisTemplate.opsForHash().put(RedisKeys.taskDraftClaim(ARTICLE_ID), "junk", "junk");

        assertThatThrownBy(() -> claimService.acquire(ARTICLE_ID))
                .as("认领键 TYPE 损坏必须 fail-closed 拒绝(不触达远端)")
                .isInstanceOf(com.choucj.aiaggregator.common.exception.NonRetryableException.class)
                .hasMessageContaining("TYPE");
        assertThat(redisTemplate.hasKey(RedisKeys.taskDraftReconcile(ARTICLE_ID)))
                .as("拒绝路径不得写入对账收据键(零写入)")
                .isFalse();
        assertThat(claimService.readReconcileReceipt(ARTICLE_ID)).isEmpty();
    }

    @Test
    void shouldRejectClaimForIllegalArticleIdWithoutAnyWrite() {
        String illegalId = "oops"; // 不匹配 (tw|gh)-[A-Za-z0-9_-]+ 白名单(键注入防御)

        assertThatThrownBy(() -> claimService.acquire(illegalId))
                .as("非法 articleId 须映射为 NonRetryableException(契约声明)")
                .isInstanceOf(com.choucj.aiaggregator.common.exception.NonRetryableException.class)
                .hasMessageContaining("articleId 非法");
        assertThat(redisTemplate.hasKey("task:{queue}:draft-claim:" + illegalId))
                .as("拒绝路径零写入: 不得创建认领键")
                .isFalse();
        assertThat(redisTemplate.hasKey("task:{queue}:draft-reconcile:" + illegalId))
                .as("拒绝路径零写入: 不得创建对账收据键")
                .isFalse();
    }
}
