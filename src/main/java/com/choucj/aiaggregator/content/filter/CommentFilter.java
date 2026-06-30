package com.choucj.aiaggregator.content.filter;

import com.choucj.aiaggregator.content.filter.config.FilterProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 评论数筛选器 (Story 2.3b, PRD FR3 第一级筛选).
 *
 * <p>纯内存比较, 零外部调用, 零成本. 剔除 {@code replyCount &lt; commentThreshold} 的 Tweet,
 * 让低互动内容不进入下一级 ({@link InnovationFilter}) 的 LLM 评分, 节省 80%+ Token.
 *
 * <p>引用源: Story 2.1/2.2 已通过 RSSHub + FxTwitter + twscrape 三链补全 {@code Tweet.replyCount}.
 *
 * <p>架构 delta (Story 2.3b): 责任链第一级, 由 Pipeline (Story 2.6) 编排调用.
 *
 * <p><b>Story 2.6 顺手补:</b> 类注解加 {@code @Order(100)} 显式声明在 Spring
 * {@code List<ContentFilter<Tweet>>} 注入排序中先于 {@link InnovationFilter} (@Order(200)) 执行.
 * 原 Story 2.3b 仅 {@code @Component} 无 {@code @Order}, 顺序依赖 Bean 注册时机不稳定.
 */
@Slf4j
@Component
@Order(100)
public class CommentFilter implements ContentFilter<Tweet> {

    private final FilterProperties properties;

    /**
     * 构造器注入 {@link FilterProperties} 以读取 {@code commentThreshold}.
     */
    public CommentFilter(FilterProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<Tweet> filter(List<Tweet> items) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        int threshold = properties.getCommentThreshold();
        List<Tweet> kept = new ArrayList<>(items.size());
        for (Tweet tweet : items) {
            if (tweet.getReplyCount() >= threshold) {
                kept.add(tweet);
            }
        }
        log.info("评论数筛选: 输入={}, 通过={}, 阈值={}", items.size(), kept.size(), threshold);
        return kept;
    }
}
