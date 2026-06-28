package com.choucj.aiaggregator.common.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 微信公众号 (WxJava) 配置属性.
 *
 * <p>绑定 {@code wx.mp.*} 配置项, 密钥通过 {@code api-keys.yml} 或环境变量注入.
 * {@link #toString} 不输出 appId / secret 字段以避免日志泄露.
 */
@ConfigurationProperties(prefix = "wx.mp")
@Validated
@Data
@ToString(exclude = {"appId", "secret"})
public class WxMpProperties {

    @NotBlank(message = "wx.mp.app-id must be configured (set WX_MP_APP_ID env var or fill api-keys.yml)")
    private String appId;

    @NotBlank(message = "wx.mp.secret must be configured (set WX_MP_SECRET env var or fill api-keys.yml)")
    private String secret;

    /**
     * 是否使用稳定版 access_token (WxJava 4.6+ 推荐).
     */
    private boolean useStableToken = true;
}
