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
 *   <li>{@code wechat.mp.*} — 项目层包装配置 (发布开关 / 草稿默认值 / AI 声明模板), 由本类消费</li>
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

    /**
     * 草稿封面 media_id (微信公众号后台展示).
     *
     * <p>Story 3.3 AC-10 要求覆盖 Story 3.2 converter 占位的空串: 微信 draft/add 接口要求
     * thumb_media_id 必须为有效的永久素材 media_id (上传一次后所有草稿复用). 运维需先调用
     * {@code /cgi-bin/material/add_material} 上传封面图获取 media_id, 填入此配置.
     *
     * <p>留空 (null/blank) 时 {@link com.choucj.aiaggregator.publish.wechat.WeChatPublisher}
     * 抛 {@code NonRetryableException(WECHAT_API_ERROR)} fail-fast, 阻止空 thumb_media_id 流到微信 API.
     */
    private String thumbMediaId;

    /** AI 辅助生成声明模板 (合规要求 AR8) — Article.aiGenerated=true 时追加到草稿尾部. */
    private String aiGeneratedDisclaimer = "本文由 AI 辅助生成, 已通过人工审核.";

    /** WxMpService 调用配置 (复用 starter 注册的 WxMpService Bean). */
    private final ServiceClient client = new ServiceClient();

    @Data
    public static class ServiceClient {

        /**
         * 是否启用 stable access token (避免高频刷新触发微信限流).
         * <p>starter 4.6.0 默认 WxMpDefaultConfigImpl 未开启 stable token, 需 Story 3.1 通过
         * 自定义 {@code WxMpConfigStorage} Bean 覆盖, 调用 {@code useStableAccessToken(true)}.
         */
        private boolean stableAccessToken = true;
    }
}
