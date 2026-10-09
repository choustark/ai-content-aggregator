package com.choucj.aiaggregator.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story 10.12: 媒体交付链路 (Story 10.7-10.11) 按类型与阶段的低基数成功率指标。
 *
 * <p>Meter 契约如下：
 * <ul>
 *   <li>{@code aiaggregator.media.phase.result}（Prometheus:
 *       {@code aiaggregator_media_phase_result_total}），Counter/媒体阶段结果数，标签
 *       {@code type=photo|video}、{@code phase=download|wechat_prepare|article_reference}、
 *       {@code outcome=succeeded|failed_terminal|retry_scheduled|blocked} 与
 *       {@code errorClass=none|environment_blocked|rate_limited|retryable|permanent}。</li>
 * </ul>
 *
 * <p><b>记录点 (观测旁路, 不改变既有状态机/幂等/重试/终态语义):</b>
 * <ul>
 *   <li>{@code download} — {@code TweetMediaArchiver} 下载成功写 {@code succeeded};
 *       下载失败唯一汇聚点 {@code logFailedDownload} 写 {@code failed_terminal}
 *       (下载阶段只有 succeeded/failed_terminal, 不产生 {@code retry_scheduled};
 *       可重试下载异常仍终态, errorClass=retryable 仅记录异常性质)</li>
 *   <li>{@code wechat_prepare} — {@code WeChatMediaPreparer} 上传成功写 {@code succeeded};
 *       可重试分类写 {@code retry_scheduled} (errorClass=retryable|rate_limited);
 *       环境阻塞 (40164) 写 {@code blocked+environment_blocked} (立即终态零重试);
 *       其余终态写 {@code failed_terminal+permanent}</li>
 *   <li>{@code article_reference} — {@code TweetMediaArchiveWriter.markArticleReferencesSucceeded}
 *       引用成功单点写 {@code succeeded}</li>
 * </ul>
 *
 * <p><b>低基数纪律 (镜像 {@link TaskMetrics}):</b> API 只接受封闭枚举 — type/phase/outcome/errorClass
 * 全部为固定值域, 各类 ID/URL/correlationId/异常文本只进 JSON 日志, 绝不进入标签;
 * 不引入 Trace 与新日志后端。Counter 仅按下方矩阵登记「可达组合」, 不可达组合
 * (如 {@code download+retry_scheduled}) 不注册即不暴露, 从结构上保证矩阵语义。
 * publishability gate 跳过与幂等跳过不产生指标 (零副作用); GIF (DEFERRED) 不建指标。
 * errorClass 值域直接对应 {@code MediaPreparationResult.FailureClass} 四分类 + {@code none}。
 */
@Slf4j
@Component
public class MediaMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<PhaseResultKey, Counter> counters = new ConcurrentHashMap<>();

    @Autowired
    public MediaMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 预登记全部可达组合 (类型×阶段×结果×错误分类 = 2×9 = 18 条时间序列, 封闭且有界)。
        for (MediaType type : MediaType.values()) {
            register(type, MediaPhase.DOWNLOAD, MediaOutcome.SUCCEEDED, MediaErrorClass.NONE);
            register(type, MediaPhase.DOWNLOAD, MediaOutcome.FAILED_TERMINAL, MediaErrorClass.RETRYABLE);
            register(type, MediaPhase.DOWNLOAD, MediaOutcome.FAILED_TERMINAL, MediaErrorClass.PERMANENT);
            register(type, MediaPhase.WECHAT_PREPARE, MediaOutcome.SUCCEEDED, MediaErrorClass.NONE);
            register(type, MediaPhase.WECHAT_PREPARE, MediaOutcome.RETRY_SCHEDULED, MediaErrorClass.RETRYABLE);
            register(type, MediaPhase.WECHAT_PREPARE, MediaOutcome.RETRY_SCHEDULED, MediaErrorClass.RATE_LIMITED);
            register(type, MediaPhase.WECHAT_PREPARE, MediaOutcome.BLOCKED, MediaErrorClass.ENVIRONMENT_BLOCKED);
            register(type, MediaPhase.WECHAT_PREPARE, MediaOutcome.FAILED_TERMINAL, MediaErrorClass.PERMANENT);
            register(type, MediaPhase.ARTICLE_REFERENCE, MediaOutcome.SUCCEEDED, MediaErrorClass.NONE);
        }
    }

    /**
     * 记录一次媒体阶段结果; 调用方只允许传入封闭枚举的可达组合。
     *
     * <p>观测是旁路: 查表 + Counter.increment 不抛业务异常, 不改变调用方状态迁移结果;
     * 不可达组合 (未预登记) 只告警丢弃, 不虚增任何可达计数。
     */
    public void record(MediaType type, MediaPhase phase, MediaOutcome outcome, MediaErrorClass errorClass) {
        PhaseResultKey key = new PhaseResultKey(type, phase, outcome, errorClass);
        Counter counter = counters.get(key);
        if (counter == null) {
            log.warn("媒体阶段指标不可达组合被丢弃 (低基数矩阵外): type={}, phase={}, outcome={}, errorClass={}",
                    type.tagValue(), phase.tagValue(), outcome.tagValue(), errorClass.tagValue());
            return;
        }
        counter.increment();
    }

    /** 成功结果便捷重载 (errorClass=none). */
    public void recordSucceeded(MediaType type, MediaPhase phase) {
        record(type, phase, MediaOutcome.SUCCEEDED, MediaErrorClass.NONE);
    }

    private void register(MediaType type, MediaPhase phase, MediaOutcome outcome, MediaErrorClass errorClass) {
        PhaseResultKey key = new PhaseResultKey(type, phase, outcome, errorClass);
        counters.put(key, Counter.builder("aiaggregator.media.phase.result")
                .description("Media delivery phase results by type/phase/outcome/errorClass (Story 10.12)")
                .tags("type", type.tagValue(), "phase", phase.tagValue(),
                        "outcome", outcome.tagValue(), "errorClass", errorClass.tagValue())
                .register(registry));
    }

    /** 媒体类型值域 — GIF/UNKNOWN 不入指标 (Story 10.12 Never: 不为 GIF 建指标). */
    public enum MediaType {
        PHOTO("photo"), VIDEO("video");

        private final String tagValue;

        MediaType(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    /** 媒体阶段值域 — 与 sidecar 三阶段证据 (download/wechatPrepare/articleReference) 对齐. */
    public enum MediaPhase {
        DOWNLOAD("download"), WECHAT_PREPARE("wechat_prepare"), ARTICLE_REFERENCE("article_reference");

        private final String tagValue;

        MediaPhase(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    /**
     * 阶段结果值域.
     *
     * <ul>
     *   <li>{@code succeeded} — 阶段成功 (含 article_reference 引用校验通过)</li>
     *   <li>{@code failed_terminal} — 终态失败, 不再重试 (下载失败恒为此值)</li>
     *   <li>{@code retry_scheduled} — 仅 wechat_prepare 在实际发生有限重试时计;
     *       download 阶段不产生该值</li>
     *   <li>{@code blocked} — 仅 wechat_prepare 环境阻塞 (40164) 立即终态零重试时计</li>
     * </ul>
     */
    public enum MediaOutcome {
        SUCCEEDED("succeeded"),
        FAILED_TERMINAL("failed_terminal"),
        RETRY_SCHEDULED("retry_scheduled"),
        BLOCKED("blocked");

        private final String tagValue;

        MediaOutcome(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    /**
     * 错误分类值域 — 直接映射 {@code MediaPreparationResult.FailureClass} 四分类,
     * 另有 {@code none} 表示成功或无分类.
     */
    public enum MediaErrorClass {
        NONE("none"),
        ENVIRONMENT_BLOCKED("environment_blocked"),
        RATE_LIMITED("rate_limited"),
        RETRYABLE("retryable"),
        PERMANENT("permanent");

        private final String tagValue;

        MediaErrorClass(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    /** 封闭组合键 — 仅由四个枚举构成, 无任何高基数自由文本. */
    private record PhaseResultKey(MediaType type, MediaPhase phase, MediaOutcome outcome, MediaErrorClass errorClass) {
    }
}
