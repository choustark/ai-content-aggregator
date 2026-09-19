package com.choucj.aiaggregator.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 外部依赖调用的低基数指标入口。
 *
 * <p>调用方只能使用封闭枚举，不能把 URL、Redis key、业务 ID 或异常文本带入标签。
 */
@Component
public class DependencyMetrics {

    /** 单次依赖操作耗时 Timer 名称。 */
    public static final String DURATION_METRIC = "aiaggregator.dependency.operation.duration";

    /** 外部依赖连续失败 Gauge 名称。 */
    public static final String CONSECUTIVE_FAILURES_METRIC =
            "aiaggregator.external.dependency.consecutive.failures";

    private final MeterRegistry registry;
    private final Map<Dependency, AtomicInteger> consecutiveFailures = new EnumMap<>(Dependency.class);
    /** Timer 实例缓存: 封闭枚举组合有限, 避免热路径每次调用都走 builder + registry 查找/注册。 */
    private final ConcurrentHashMap<TimerKey, Timer> timers = new ConcurrentHashMap<>();

    /** 创建指标组件并为每个封闭依赖注册连续失败 Gauge。 */
    public DependencyMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Dependency dependency : Dependency.values()) {
            AtomicInteger failures = new AtomicInteger();
            consecutiveFailures.put(dependency, failures);
            Gauge.builder(CONSECUTIVE_FAILURES_METRIC, failures, AtomicInteger::get)
                    .description("Consecutive failures observed at an external dependency boundary")
                    .tag("dependency", dependency.tagValue())
                    .register(registry);
        }
    }

    /** 记录一次完整的实际外部调用，并更新对应依赖的连续失败状态。 */
    public void record(Kind kind, Dependency dependency, Operation operation, Outcome outcome,
                       Duration elapsed) {
        timerFor(kind, dependency, operation, outcome).record(elapsed);
        if (outcome == Outcome.SUCCESS) {
            consecutiveFailures.get(dependency).set(0);
        } else {
            consecutiveFailures.get(dependency).incrementAndGet();
        }
    }

    private Timer timerFor(Kind kind, Dependency dependency, Operation operation, Outcome outcome) {
        return timers.computeIfAbsent(new TimerKey(kind, dependency, operation, outcome), key ->
                Timer.builder(DURATION_METRIC)
                        .description("Duration of a completed external dependency operation")
                        .tags("kind", kind.tagValue(),
                                "dependency", dependency.tagValue(),
                                "operation", operation.tagValue(),
                                "outcome", outcome.tagValue())
                        .register(registry));
    }

    /** 封闭枚举组合的 Timer 缓存键。 */
    private record TimerKey(Kind kind, Dependency dependency, Operation operation, Outcome outcome) {
    }

    /** 外部调用技术形态。 */
    public enum Kind {
        REDIS("redis"), HTTP("http"), SDK("sdk");

        private final String tagValue;

        Kind(String tagValue) {
            this.tagValue = tagValue;
        }

        /** 返回稳定的低基数标签值。 */
        public String tagValue() {
            return tagValue;
        }
    }

    /** 当前代码库中允许观测的外部依赖。 */
    public enum Dependency {
        REDIS("redis"),
        GITHUB("github"),
        RSSHUB("rsshub"),
        FXTWITTER("fxtwitter"),
        APIFY("apify"),
        LOCAL_SCRAPER("local_scraper"),
        TWITTER_MEDIA("twitter_media"),
        LLM_DEEPSEEK("llm_deepseek"),
        LLM_GLM("llm_glm"),
        EMBEDDING("embedding"),
        WECHAT("wechat");

        private final String tagValue;

        Dependency(String tagValue) {
            this.tagValue = tagValue;
        }

        /** 返回稳定的低基数标签值。 */
        public String tagValue() {
            return tagValue;
        }
    }

    /** 当前外部边界允许使用的语义化操作。 */
    public enum Operation {
        GET("get"),
        EXISTS("exists"),
        SET("set"),
        EXPIRE("expire"),
        DELETE("delete"),
        INCREMENT("increment"),
        ENQUEUE("enqueue"),
        ADD("add"),
        REMOVE("remove"),
        POLL("poll"),
        RECOVERY("recovery"),
        DISCOVER("discover"),
        FETCH("fetch"),
        CREATE_JOB("create_job"),
        POLL_JOB("poll_job"),
        DOWNLOAD("download"),
        CHAT("chat"),
        EMBED("embed"),
        STORE("store"),
        SEARCH("search"),
        AUTHENTICATE("authenticate"),
        PUBLISH("publish"),
        UPLOAD("upload");

        private final String tagValue;

        Operation(String tagValue) {
            this.tagValue = tagValue;
        }

        /** 返回稳定的低基数标签值。 */
        public String tagValue() {
            return tagValue;
        }
    }

    /** 外部调用结果分类。 */
    public enum Outcome {
        SUCCESS("success"),
        CLIENT_ERROR("client_error"),
        SERVER_ERROR("server_error"),
        TIMEOUT("timeout"),
        FAILURE("failure");

        private final String tagValue;

        Outcome(String tagValue) {
            this.tagValue = tagValue;
        }

        /** 返回稳定的低基数标签值。 */
        public String tagValue() {
            return tagValue;
        }
    }
}
