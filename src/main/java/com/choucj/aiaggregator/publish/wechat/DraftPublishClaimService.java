package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.util.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.ClusterStateFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Story 10.16 — 微信草稿发布原子认领服务 (跨实例 SETNX+TTL 租约, Lua 同槽).
 *
 * <p><b>问题:</b> 微信 {@code addDraft} 无唯一性约束, 10.6 的「快照先查后写」幂等守卫不具备
 * 跨实例原子性 — 两实例并发发布同一文章会双双通过守卫、重复调用 addDraft 产生重复草稿。
 *
 * <p><b>方案:</b> 发布工作流在幂等守卫之后、{@code addDraft} 之前经本服务对
 * {@code task:{queue}:draft-claim:<articleId>} 执行原子认领(稳定业务键 = articleId):
 * <ul>
 *   <li>认领脚本(2 键同槽, 与对账收据键 {@code task:{queue}:draft-reconcile:&lt;articleId&gt;}
 *       共享 {@code task:{queue}:} hash tag):
 *       对账收据已存在 → 返回 {@code SKIP_EXISTING_RECEIPT}(幂等跳过, 只补本地写回);
 *       租约已被持有 → 返回 {@code SKIP_HELD}(另一实例在途); 否则 SET(值=holderId, PX 租约 TTL) →
 *       {@code ACQUIRED}。</li>
 *   <li>租约 TTL 覆盖「addDraft 调用 + 收据写回」时长上限(分钟级, 默认 10 分钟):
 *       进程崩溃残留靠 TTL 自动过期自愈, 不需要人工清理。</li>
 *   <li>释放脚本: 仅当租约值等于本实例 holderId 时 DEL — 不误删他人租约, 幂等可重入。</li>
 *   <li>对账收据脚本: 仅持有者可写 — 原子完成「HSET 收据(mediaId/recordedAt) + DEL 租约」,
 *       供「远端成功、本地写回失败」时留存已知 mediaId; 对账补全只读该收据做本地写回,
 *       <b>绝不重发远端调用</b>。</li>
 * </ul>
 *
 * <p><b>Lua 参数约定(与 TaskQueue 一致):</b> args 全部传 {@link String}, resultType 收敛
 * {@code Long.class}, 所有脚本在首个写操作前校验目标键 TYPE, 类型不符返回 {@code -1} 拒绝且
 * 零写入, 映射 {@link NonRetryableException}(数据损坏, 需运维介入)。
 *
 * <p><b>语义边界:</b> 认领是 10.6 幂等判定的前置加固, 不替代其判定条件;
 * 不承诺 exactly-once — 「addDraft 响应丢失且对账收据也未写入」的极端窗口属已接受残留。
 */
@Service
@Slf4j
public class DraftPublishClaimService {

    /** 默认租约 TTL: 覆盖 addDraft 调用 + 三处收据写回的时长上限(分钟级, 设计注记). */
    static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(10);

    /** CLAIM 脚本返回码: 认领成功(租约已写入). */
    private static final long CLAIM_RESULT_ACQUIRED = 1L;
    /** CLAIM 脚本返回码: 租约已被其他实例持有(在途). */
    private static final long CLAIM_RESULT_HELD = 2L;
    /** CLAIM 脚本返回码: 对账收据已存在(既有远端副作用, 只许本地补账). */
    private static final long CLAIM_RESULT_RECEIPT_EXISTS = 3L;
    /** 所有脚本共用返回码: 键 TYPE 前置校验拒绝, 未修改任何键. */
    private static final long SCRIPT_RESULT_TYPE_REJECTED = -1L;

    /**
     * CLAIM 脚本(2 键同槽): KEYS=[draft-claim, draft-reconcile], ARGV=[holderId, ttlMillis].
     *
     * <p>TYPE 校验 → 收据存在即 3(不写任何键) → 租约被持有即 2 → SET PX 认领返回 1。
     * 收据检查先于租约检查: 「重复补跑命中收据」优先按幂等跳过处理。
     */
    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local claimType = redis.call('TYPE', KEYS[1])['ok']
            if claimType ~= 'none' and claimType ~= 'string' then return -1 end
            local reconcileType = redis.call('TYPE', KEYS[2])['ok']
            if reconcileType ~= 'none' and reconcileType ~= 'hash' then return -1 end
            if redis.call('EXISTS', KEYS[2]) == 1 then return 3 end
            if redis.call('EXISTS', KEYS[1]) == 1 then return 2 end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 1
            """, Long.class);

    /**
     * RELEASE 脚本(1 键): KEYS=[draft-claim], ARGV=[holderId].
     *
     * <p>仅租约值等于本实例 holderId 时 DEL(防误删并发新持有者的租约), 返回 1=已释放 / 0=非持有者
     * (已过期或被他人持有, 幂等 no-op) / -1=TYPE 拒绝。
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local claimType = redis.call('TYPE', KEYS[1])['ok']
            if claimType ~= 'none' and claimType ~= 'string' then return -1 end
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end
            return 0
            """, Long.class);

    /**
     * RECORD_RECONCILE 脚本(2 键同槽): KEYS=[draft-claim, draft-reconcile],
     * ARGV=[holderId, mediaId, articleId, recordedAt].
     *
     * <p>仅当前租约持有者可写(防过期后新实例竞争时写入陈旧收据): 原子完成 DEL 租约 +
     * HSET 收据三字段, 返回 1=已记录 / 0=非持有者(租约已过期, 收据不写 — 交由残留路径处理) /
     * -1=TYPE 拒绝。
     */
    private static final DefaultRedisScript<Long> RECORD_RECONCILE_SCRIPT = new DefaultRedisScript<>("""
            local claimType = redis.call('TYPE', KEYS[1])['ok']
            if claimType ~= 'none' and claimType ~= 'string' then return -1 end
            local reconcileType = redis.call('TYPE', KEYS[2])['ok']
            if reconcileType ~= 'none' and reconcileType ~= 'hash' then return -1 end
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1])
            redis.call('DEL', KEYS[2])
            redis.call('HSET', KEYS[2], 'articleId', ARGV[3], 'mediaId', ARGV[2],
                'recordedAt', ARGV[4])
            return 1
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    /** 本服务实例的租约持有标识(进程内唯一, 区分多实例)。 */
    private final String holderId = UUID.randomUUID().toString();

    public DraftPublishClaimService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 认领结果 — 调用方(发布工作流)据此分支: 只有 {@link #ACQUIRED} 允许触达远端 addDraft. */
    public enum ClaimOutcome {
        /** 认领成功, 持有租约, 可执行 addDraft. */
        ACQUIRED,
        /** 租约被其他实例持有(并发在途) — 不触达远端, 按幂等跳过处理并记指标. */
        SKIP_HELD,
        /** 对账收据已存在(既有远端副作用) — 不触达远端, 只做本地对账补全. */
        SKIP_EXISTING_RECEIPT
    }

    /** 对账收据(异常窗口内留存的已知 mediaId, 供只补本地写回). */
    public record ReconcileReceipt(String articleId, String mediaId, String recordedAt) {
    }

    /**
     * 原子认领指定文章的草稿发布权(默认租约 TTL).
     *
     * @throws NonRetryableException 键 TYPE 校验拒绝(数据损坏) / articleId 非法
     * @throws RetryableException    Redis 连接异常
     */
    public ClaimOutcome acquire(String articleId) {
        return acquire(articleId, DEFAULT_LEASE_TTL);
    }

    /**
     * 原子认领指定文章的草稿发布权(显式租约 TTL, 供集成测试压缩过期窗口).
     *
     * @throws NonRetryableException 键 TYPE 校验拒绝(数据损坏) / articleId 非法
     * @throws RetryableException    Redis 连接异常
     */
    public ClaimOutcome acquire(String articleId, Duration leaseTtl) {
        try {
            return doAcquire(articleId, leaseTtl);
        } catch (IllegalArgumentException e) {
            // RedisKeys.requireArticleId 键注入白名单拒绝 — 映射为声明的 NonRetryable 契约
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "草稿认领失败: articleId 非法: " + e.getMessage(), e);
        }
    }

    private ClaimOutcome doAcquire(String articleId, Duration leaseTtl) {
        if (leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "草稿认领失败: 租约 TTL 必须为正: articleId=" + articleId);
        }
        Long result = supplyWithMapping("draft-claim", RedisKeys.taskDraftClaim(articleId),
                () -> stringRedisTemplate.execute(CLAIM_SCRIPT,
                        List.of(RedisKeys.taskDraftClaim(articleId),
                                RedisKeys.taskDraftReconcile(articleId)),
                        holderId, String.valueOf(leaseTtl.toMillis())));
        if (result == null) {
            throw new RetryableException("草稿认领失败(Redis 返回空): articleId=" + articleId);
        }
        if (result == CLAIM_RESULT_ACQUIRED) {
            log.info("草稿发布租约认领成功: articleId={}, leaseTtlMillis={}",
                    articleId, leaseTtl.toMillis());
            return ClaimOutcome.ACQUIRED;
        }
        if (result == CLAIM_RESULT_HELD) {
            log.warn("草稿发布租约被其他实例持有, 跳过远端发布(并发幂等): articleId={}", articleId);
            return ClaimOutcome.SKIP_HELD;
        }
        if (result == CLAIM_RESULT_RECEIPT_EXISTS) {
            log.warn("草稿对账收据已存在, 跳过远端发布(claim 收据命中): articleId={}", articleId);
            return ClaimOutcome.SKIP_EXISTING_RECEIPT;
        }
        throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                "draft-claim 失败(键 TYPE 前置校验拒绝, 疑似数据损坏): articleId=" + articleId);
    }

    /**
     * 释放本实例持有的租约(幂等: 非持有者/已过期均 no-op 返回 false).
     *
     * @throws RetryableException Redis 连接异常
     */
    public boolean release(String articleId) {
        Long result = supplyWithMapping("draft-claim-release",
                RedisKeys.taskDraftClaim(articleId),
                () -> stringRedisTemplate.execute(RELEASE_SCRIPT,
                        List.of(RedisKeys.taskDraftClaim(articleId)), holderId));
        boolean released = result != null && result == 1L;
        if (!released) {
            log.info("草稿发布租约非本实例持有, 跳过释放: articleId={}", articleId);
        }
        return released;
    }

    /**
     * 持有者身份下原子留存对账收据并释放租约 — 供「远端成功、本地写回失败」异常路径调用.
     *
     * <p>租约已过期(返回 0)时不写收据并抛 {@link RetryableException}: 此时可能有新实例已认领,
     * 陈旧 mediaId 不得覆盖新持有者的判断窗口。
     *
     * @throws NonRetryableException 键 TYPE 校验拒绝(数据损坏)
     * @throws RetryableException    Redis 连接异常 / 租约已丢失
     */
    public void recordReconcileReceipt(String articleId, String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "记录草稿对账收据失败: mediaId 不能为空: articleId=" + articleId);
        }
        Long result = supplyWithMapping("draft-reconcile-record",
                RedisKeys.taskDraftReconcile(articleId),
                () -> stringRedisTemplate.execute(RECORD_RECONCILE_SCRIPT,
                        List.of(RedisKeys.taskDraftClaim(articleId),
                                RedisKeys.taskDraftReconcile(articleId)),
                        holderId, mediaId, articleId, Instant.now().toString()));
        if (result != null && result == 1L) {
            log.warn("草稿对账收据已留存并释放租约(远端成功, 本地写回待补): articleId={}, mediaIdPrefix={}",
                    articleId, maskMediaId(mediaId));
            return;
        }
        if (result != null && result == SCRIPT_RESULT_TYPE_REJECTED) {
            // 与类 javadoc 契约一致: TYPE 前置校验拒绝属数据损坏, 重试无意义
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "draft-reconcile-record 失败(键 TYPE 前置校验拒绝, 疑似数据损坏): articleId="
                            + articleId);
        }
        throw new RetryableException(
                "草稿对账收据写入失败(租约已丢失, 拒绝写入陈旧收据): articleId=" + articleId);
    }

    /** 读取对账收据(只读); 无记录或字段缺失返回 empty. */
    public Optional<ReconcileReceipt> readReconcileReceipt(String articleId) {
        String reconcileKey = RedisKeys.taskDraftReconcile(articleId);
        Map<Object, Object> entries = supplyWithMapping("draft-reconcile-read", reconcileKey,
                () -> stringRedisTemplate.opsForHash().entries(reconcileKey));
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> receipt = entries.entrySet().stream()
                .collect(Collectors.toMap(e -> String.valueOf(e.getKey()),
                        e -> String.valueOf(e.getValue())));
        String mediaId = receipt.get("mediaId");
        if (mediaId == null || mediaId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ReconcileReceipt(receipt.get("articleId"), mediaId,
                receipt.get("recordedAt")));
    }

    /**
     * 对账补全成功后清除收据(幂等; 仅清除本地镜像, 远端草稿不受影响).
     *
     * @throws RetryableException Redis 连接异常
     */
    public void clearReconcileReceipt(String articleId) {
        String reconcileKey = RedisKeys.taskDraftReconcile(articleId);
        supplyWithMapping("draft-reconcile-clear", reconcileKey,
                () -> stringRedisTemplate.delete(reconcileKey));
        log.info("草稿对账收据已清除(对账补全完成): articleId={}", articleId);
    }

    /** 日志脱敏: draftMediaId 仅前缀(Story 10.16 Always 约束). */
    static String maskMediaId(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            return "***";
        }
        return mediaId.length() <= 8
                ? mediaId.charAt(0) + "***"
                : mediaId.substring(0, 8) + "***";
    }

    private <T> T supplyWithMapping(String operation, String key, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RedisConnectionFailureException | QueryTimeoutException
                 | ClusterStateFailureException e) {
            log.warn("Redis {} 异常映射为 RetryableException: key={}", operation, key, e);
            throw new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR,
                    operation + " 失败(Redis 连接异常): key=" + key, e);
        } catch (InvalidDataAccessApiUsageException | SerializationException e) {
            log.warn("Redis {} 异常映射为 NonRetryableException: key={}", operation, key, e);
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    operation + " 失败(Redis 数据异常): key=" + key, e);
        } catch (RedisSystemException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InvalidDataAccessApiUsageException
                    || cause instanceof SerializationException) {
                throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                        operation + " 失败(Redis 数据异常, 根因透传): key=" + key, e);
            }
            log.warn("Redis {} 异常保守归 RetryableException: key={}", operation, key, e);
            throw new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR,
                    operation + " 失败(Redis 系统异常, 保守归可重试): key=" + key, e);
        }
    }
}
