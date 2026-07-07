package com.choucj.aiaggregator.publish.wechat.config;

import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import me.chanjar.weixin.mp.config.WxMpConfigStorage;
import me.chanjar.weixin.mp.config.impl.WxMpDefaultConfigImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 微信公众号发布项目层 Bean 装配.
 *
 * <p>装配职责:
 * <ul>
 *   <li>注册 {@link WeChatProperties}</li>
 *   <li>按 {@code wechat.mp.enabled} 条件注册 {@code WeChatPublisher} Bean</li>
 *   <li>按 {@code wechat.mp.client.stable-access-token=true} 条件覆盖默认 {@code WxMpConfigStorage} Bean
 *       (启用 stable access token, 避免 starter 4.6.0 默认行为)</li>
 * </ul>
 *
 * <p><b>不重复注册 {@code WxMpService} Bean</b> — starter 4.6.0 已通过 {@code WxMpServiceAutoConfiguration}
 * 自动注册 (HttpClient 实现, 默认 {@code @ConditionalOnMissingBean}), 项目层直接 @Autowired 注入即可.
 *
 * @see WeChatProperties
 */
@Configuration
@ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WeChatProperties.class)
public class WeChatConfig {

    /**
     * 覆盖 WxJava starter 默认 Memory 配置存储,启用 stable access token.
     *
     * <p>starter 的 {@code WxMpStorageAutoConfiguration} 同样使用
     * {@code @ConditionalOnMissingBean(WxMpConfigStorage.class)},因此项目层 Bean 存在时会跳过默认注册.
     *
     * <p>当 {@code wechat.mp.client.stable-access-token=false} 时, 本 Bean 不注册, 由 starter 默认
     * {@code WxMpConfigStorage} 接管. 当前 WxJava 4.6.0 默认 {@code stableAccessToken=false},测试会锁定该语义,
     * 防止未来 starter 默认值变化时静默改变项目行为.
     */
    @Bean
    @ConditionalOnMissingBean(WxMpConfigStorage.class)
    @ConditionalOnProperty(
            prefix = "wechat.mp.client",
            name = "stable-access-token",
            havingValue = "true",
            matchIfMissing = true)
    public WxMpConfigStorage wxMpConfigStorage(WxMpProperties wxMpProperties) {
        WxMpDefaultConfigImpl config = new WxMpDefaultConfigImpl();
        if (!StringUtils.hasText(wxMpProperties.getAppId())) {
            throw new IllegalStateException("wx.mp.app-id must be configured when wechat.mp.enabled=true");
        }
        if (!StringUtils.hasText(wxMpProperties.getSecret())) {
            throw new IllegalStateException("wx.mp.secret must be configured when wechat.mp.enabled=true");
        }
        config.setAppId(wxMpProperties.getAppId());
        config.setSecret(wxMpProperties.getSecret());
        if (wxMpProperties.getToken() != null) {
            config.setToken(wxMpProperties.getToken());
        }
        if (wxMpProperties.getAesKey() != null) {
            config.setAesKey(wxMpProperties.getAesKey());
        }
        config.useStableAccessToken(true);
        return config;
    }
}
