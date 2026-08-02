package com.choucj.aiaggregator.source.twitter;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.source.DataSource;
import com.choucj.aiaggregator.source.twitter.client.FxTwitterClient;
import com.choucj.aiaggregator.source.twitter.client.TwscrapeClient;
import com.choucj.aiaggregator.source.twitter.config.FxTwitterProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterProperties;
import com.choucj.aiaggregator.source.twitter.config.TwscrapeProperties;
import com.choucj.aiaggregator.source.twitter.discovery.TwitterDiscoveryClient;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Twitter 数据源编排器 — 实现 {@link DataSource} 通用接口.
 *
 * <p>编排 {@link TwitterDiscoveryClient}(发现) + {@link TwscrapeClient}(主路径补全) +
 * {@link FxTwitterClient}(降级补全) + Redis 缓存(tweet:{id}, 24h TTL),
 * 对外暴露统一的 {@link #fetch()} 方法供 Pipeline / Scheduler 调用.
 *
 * <p><b>fetch() 编排流程:</b>
 * <ol>
 *   <li>遍历 {@code twitter.accounts} 列表, 逐个调 {@link TwitterDiscoveryClient#discoverTweets(String)}</li>
 *   <li>对每条发现返回的 Tweet 调 {@link #enrichTweet(Tweet)}</li>
 *   <li>enrichTweet: 先查 {@code tweet:{id}} Redis 缓存 → 命中直接合并 →
 *       未命中走 twscrape 主 → FxTwitter 备 双链降级, 用 {@link Tweet#toBuilder()} 合并 → 写回缓存</li>
 * </ol>
 *
 * <p><b>双链降级模式 (Story 2.2b):</b>
 * <ul>
 *   <li>{@code twscrape.enabled=true}(默认): twscrape 作为主路径, 失败落到 FxTwitter</li>
 *   <li>{@code twscrape.enabled=false}: 直接走 FxTwitter(等价 Story 2.2a 单链行为)</li>
 *   <li>{@code fxtwitter.enabled=false}: 仅 twscrape; twscrape 失败 → 剔除 Tweet</li>
 *   <li>两者均 {@code enabled=false}: 降级返回 RSSHub 部分字段(id/author/summary/url/publishedAt)</li>
 * </ul>
 *
 * <p><b>失败跳过语义:</b>
 * <ul>
 *   <li>单账号失败(RSSHub 抛异常): 跳过该账号, 继续下一个, 不中断 fetch()</li>
 *   <li>单 Tweet 补全失败(双链均失败): 剔除该 Tweet, 不写缓存(避免毒化), 继续其他</li>
 * </ol>
 *
 * <p>架构 delta (Story 2.2a): 失败跳过不抛异常的设计与 RSSHub 多实例备份不同 — 此处面向最终用户,
 * 单条失败可接受(部分内容总比全无强);底层客户端异常分类仍保留供调用方(如重试调度器)决策.
 *
 * <p>架构 delta (Story 2.2b): twscrape 主路径 + FxTwitter 降级路径的双链编排; 缓存命中优先级最高,
 * 避免无谓的双链调用降低外部依赖压力.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TwitterSource implements DataSource<Tweet> {

    /** tweet:{id} 缓存 TTL. 24h 平衡新鲜度与限流风险. */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final TwitterDiscoveryClient discoveryClient;
    private final FxTwitterClient fxTwitterClient;
    private final TwscrapeClient twscrapeClient;
    private final RedisRepository redisRepository;
    private final TwitterProperties twitterProperties;
    private final FxTwitterProperties fxTwitterProperties;
    private final TwscrapeProperties twscrapeProperties;

    @Override
    public List<Tweet> fetch() {
        List<String> accounts = twitterProperties.getAccounts();
        if (accounts == null || accounts.isEmpty()) {
            log.info("twitter.accounts 未配置, 返回空列表");
            return List.of();
        }
        List<Tweet> all = new ArrayList<>();
        for (String account : accounts) {
            try {
                List<Tweet> discovered = discoveryClient.discoverTweets(account);
                for (Tweet partial : discovered) {
                    Tweet enriched = enrichTweet(partial);
                    if (enriched != null) {
                        all.add(enriched);
                    }
                }
                log.info("账号 {} 抓取完成, 发现 {} 条, 累计入库 {} 条",
                        account, discovered.size(), all.size());
            } catch (RetryableException | NonRetryableException e) {
                log.warn("账号 {} 抓取失败, 跳过: {}", account, e.getMessage());
            }
        }
        log.info("TwitterSource.fetch 完成, 账号数={}, 总条数={}", accounts.size(), all.size());
        return all;
    }

    /**
     * 双链降级补全单条 Tweet (Story 2.2b 升级).
     *
     * <p>流程:
     * <ol>
     *   <li>查 tweet:{id} Redis 缓存 → 命中 → 用缓存的补全字段 toBuilder 合并到 partial, 跳过双链调用</li>
     *   <li>twscrape + FxTwitter 均禁用 → 降级返回 partial(等价 Story 2.2a 行为)</li>
     *   <li>twscrape.enabled=true → 主路径调用 twscrapeClient.fetchTweetDetail
     *       → 成功 merge + 写缓存; 失败 warn 后落到 FxTwitter</li>
     *   <li>FxTwitter.enabled=true → 备路径调用 fxTwitterClient.fetchTweetDetail
     *       → 成功 merge + 写缓存; 失败 warn + 返回 null(剔除, 不写缓存)</li>
     *   <li>twscrape 失败 + FxTwitter 禁用 → 返回 null(剔除)</li>
     * </ol>
     *
     * <p>架构 delta (Story 2.2b): 双链降级编排 — twscrape(主, 已登录账号) → FxTwitter(备, 公共实例).
     * 缓存命中优先级最高, 避免无谓的双链调用. 失败时不写缓存(避免 24h 毒化).
     *
     * @param partial discovery provider 返回的部分 Tweet(id/author/summary/url/publishedAt 已填)
     * @return 合并后的完整 Tweet; null 表示该 Tweet 应被剔除
     */
    Tweet enrichTweet(Tweet partial) {
        boolean twsEnabled = twscrapeProperties.isEnabled();
        boolean fxEnabled = fxTwitterProperties.isEnabled();

        if (!twsEnabled && !fxEnabled) {
            log.debug("twscrape + FxTwitter 均禁用, 降级返回 RSSHub 部分字段: tweetId={}", partial.getId());
            return partial;
        }

        String cacheKey = RedisKeys.tweet(partial.getId());
        Tweet cached = readCacheOrNull(cacheKey);
        if (cached != null) {
            log.debug("tweet:{} 缓存命中, 跳过 twscrape/FxTwitter 双链调用", partial.getId());
            return mergeTweet(partial, cached);
        }

        if (twsEnabled) {
            try {
                Tweet tsTweet = twscrapeClient.fetchTweetDetail(partial.getId(), partial.getUrl());
                Tweet merged = mergeTweet(partial, tsTweet);
                writeCache(cacheKey, merged);
                return merged;
            } catch (RetryableException | NonRetryableException e) {
                if (fxEnabled) {
                    log.warn("twscrape 失败, 降级 FxTwitter: tweetId={}, reason={}",
                            partial.getId(), e.getMessage());
                } else {
                    log.warn("twscrape 失败且 FxTwitter 已禁用, 剔除 Tweet: tweetId={}, reason={}",
                            partial.getId(), e.getMessage());
                    return null;
                }
            }
        }

        try {
            Tweet fxTweet = fxTwitterClient.fetchTweetDetail(partial.getId());
            Tweet merged = mergeTweet(partial, fxTweet);
            writeCache(cacheKey, merged);
            return merged;
        } catch (RetryableException | NonRetryableException e) {
            log.warn("FxTwitter 补全失败, 剔除该 Tweet(不写缓存): tweetId={}, reason={}",
                    partial.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 合并 discovery partial 与补全结果.
     * <p>使用 {@link Tweet#toBuilder()} 保留 RSSHub 已填字段(id/author/summary/url/publishedAt),
     * 覆盖 provider 补全字段(content/互动数/imageUrls 等). 对文本/集合等可空字段采用非空优先,
     * 避免缓存或降级 provider 返回局部字段时擦掉 discovery 已有信息.
     */
    private Tweet mergeTweet(Tweet partial, Tweet enrichedTweet) {
        return partial.toBuilder()
                .content(firstText(enrichedTweet.getContent(), partial.getContent()))
                .rawText(firstText(enrichedTweet.getRawText(), partial.getRawText()))
                .formattedText(firstText(enrichedTweet.getFormattedText(), partial.getFormattedText()))
                .replyCount(enrichedTweet.getReplyCount())
                .retweetCount(enrichedTweet.getRetweetCount())
                .likeCount(enrichedTweet.getLikeCount())
                .imageUrls(firstList(enrichedTweet.getImageUrls(), partial.getImageUrls()))
                .media(firstList(enrichedTweet.getMedia(), partial.getMedia()))
                .links(firstList(enrichedTweet.getLinks(), partial.getLinks()))
                .mentions(firstList(enrichedTweet.getMentions(), partial.getMentions()))
                .quotedTweetUrl(firstText(enrichedTweet.getQuotedTweetUrl(), partial.getQuotedTweetUrl()))
                .quotedTweetText(firstText(enrichedTweet.getQuotedTweetText(), partial.getQuotedTweetText()))
                .sourceAccessNote(firstText(enrichedTweet.getSourceAccessNote(), partial.getSourceAccessNote()))
                .build();
    }

    private String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private <T> List<T> firstList(List<T> preferred, List<T> fallback) {
        return preferred == null || preferred.isEmpty() ? fallback : preferred;
    }

    private Tweet readCacheOrNull(String key) {
        try {
            return redisRepository.getObject(key, Tweet.class);
        } catch (RetryableException | NonRetryableException e) {
            log.warn("Redis 缓存读取失败, 视为未命中: key={}, reason={}", key, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, Tweet tweet) {
        try {
            redisRepository.setObject(key, tweet, CACHE_TTL);
        } catch (RetryableException | NonRetryableException e) {
            log.warn("Redis 缓存写入失败, 忽略(下次重新补全): key={}, reason={}", key, e.getMessage());
        }
    }
}
