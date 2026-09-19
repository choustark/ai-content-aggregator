package com.choucj.aiaggregator.common.observability;

import com.choucj.aiaggregator.monitoring.DependencyMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 为既有边界测试提供真实、无副作用的统一观测 recorder。 */
public final class TestSlowOperationRecorder {

    private TestSlowOperationRecorder() {
    }

    /** 创建使用独立内存 registry 的 recorder，使测试覆盖真实 supplier 执行。 */
    public static SlowOperationRecorder create() {
        return new SlowOperationRecorder(new DependencyMetrics(new SimpleMeterRegistry()),
                new SlowOperationProperties());
    }
}
