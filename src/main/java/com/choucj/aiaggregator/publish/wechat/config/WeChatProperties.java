package com.choucj.aiaggregator.publish.wechat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 微信公众号发布项目层配置.
 *
 * <p>{@code wechat.mp.*} 前缀与 WxJava starter 的 {@code wx.mp.*} 前缀<b>同时使用</b>:
 * <ul>
 *   <li>{@code wx.mp.*} — SDK 底层配置 (app-id / secret / config-storage), 由 starter {@code WxMpProperties} 直接消费</li>
 *   <li>{@code wechat.mp.*} — 项目层包装配置 (发布开关 / 草稿默认值), 由本类消费</li>
 * </ul>
 *
 * <p>设计意图: 项目层与 SDK 解耦, 将来切换微信 SDK (如改用 OkHttp 直连) 时, 仅替换 wx.mp.* 块, wechat.mp.* 业务语义保留.
 *
 * <p> spike 状态: 仅字段定义 + 校验, 业务方法实现见 Story 3.1。
 *
 * @see WeChatConfig 项目层 Bean 装配
 */
@Data
@Validated
@ConfigurationProperties(prefix = "wechat.mp")
public class WeChatProperties {

    /** 发布渠道总开关 — false 时 WeChatPublisher Bean 不注册. */
    private boolean enabled = false;

    /** 草稿默认作者名 (微信公众号后台展示). */
    private String defaultAuthor = "AI 内容聚合器";

    /** WxMpService 调用配置 (复用 starter 注册的 WxMpService Bean). */
    private final ServiceClient client = new ServiceClient();

    /** Story 10.11: VIDEO 永久素材上传配置 (Spike 10.9 §8 建议配置键落地). */
    private final Video video = new Video();

    @Data
    public static class ServiceClient {

        /**
         * 是否启用 stable access token (避免高频刷新触发微信限流).
         * <p>starter 4.6.0 默认 WxMpDefaultConfigImpl 未开启 stable token, 需 Story 3.1 通过
         * 自定义 {@code WxMpConfigStorage} Bean 覆盖, 调用 {@code useStableAccessToken(true)}.
         */
        private boolean stableAccessToken = true;
    }

    @Data
    public static class Video {

        /**
         * VIDEO 微信上传总开关 — 默认 true (Spike 10.9 已判 Go, 契约固化 §8).
         * <p>false 时 VIDEO 保持 Story 10.8 降级语义 (SKIPPED + 预览图/原文链接文案), 零上传请求.
         */
        private boolean enabled = true;

        /**
         * 本地容量上限 (MB, 默认 10) — 微信永久端点超限表现为 Read timed out 而非干净错误码,
         * 容量校验必须本地前置实施 (Spike §8 风险注记), 不得依赖微信侧语义.
         */
        private int sizeLimitMb = 10;

        /**
         * materialVideoInfo 回读尝试次数 (含首次, 默认 3) — 覆盖微信异步转码场景.
         * <p>回读为 best-effort 证据, 耗尽仅记 warn 日志, 不作为 wechatPrepare=SUCCEEDED 前置.
         */
        private int readbackAttempts = 3;

        /** 回读重试间隔 (毫秒, 默认 3000 — Spike 10.9 实测 3 次 × 3s 形态). */
        private long readbackIntervalMs = 3000L;
    }
}
