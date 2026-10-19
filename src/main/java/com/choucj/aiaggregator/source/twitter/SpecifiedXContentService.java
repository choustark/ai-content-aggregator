package com.choucj.aiaggregator.source.twitter;

import com.choucj.aiaggregator.common.exception.DegradationException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.processor.TwitterProcessor;
import com.choucj.aiaggregator.source.twitter.discovery.XAuthorScraperDiscoveryClient;
import com.choucj.aiaggregator.source.twitter.discovery.XPostTargetNormalizer;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/** 编排单个指定 X Post 的发现、双链补全与完整处理流水线。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpecifiedXContentService {

    private final XPostTargetNormalizer targetNormalizer;
    private final XAuthorScraperDiscoveryClient scraperDiscoveryClient;
    private final TwitterSource twitterSource;
    private final TwitterProcessor twitterProcessor;

    public synchronized Result process(String target) {
        XPostTargetNormalizer.Target normalized = targetNormalizer.normalize(target);
        if (normalized == null) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "目标必须是 x.com/twitter.com 的 /status/{id} 或纯数字 ID");
        }
        String tweetId = normalized.tweetId();

        long started = System.nanoTime();
        List<Tweet> tweets;
        boolean degraded = false;
        try {
            tweets = scraperDiscoveryClient.discoverSpecifiedPost(normalized);
        } catch (RetryableException | DegradationException e) {
            degraded = true;
            log.warn("指定 X Post 主发现路径失败, 启用既有补全链: tweetId={}, type={}",
                    tweetId, e.getClass().getSimpleName());
            tweets = List.of(minimalTweet(tweetId, normalized.url()));
        }

        Tweet selected = tweets.stream()
                .filter(java.util.Objects::nonNull)
                .filter(tweet -> tweetId.equals(tweet.getId()))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            String code = tweets.isEmpty() ? "POST_NOT_FOUND" : "POST_ID_MISMATCH";
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "本地指定 Post 结果无效: postId=" + tweetId + ", code=" + code);
        }
        Tweet targetTweet = selected;
        if (degraded) {
            targetTweet = twitterSource.enrichSpecifiedTweets(List.of(selected)).stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(tweet -> tweetId.equals(tweet.getId()))
                    .findFirst()
                    .orElse(null);
        }
        if (!isProcessable(targetTweet)) {
            throw new DegradationException("指定 X Post 的主发现与补全路径均不可用, tweetId=" + tweetId);
        }

        TwitterProcessor.ProcessingSummary summary = twitterProcessor.processTweets(List.of(targetTweet));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        return new Result(tweetId, summary.discovered(), summary.filtered(), summary.archived(),
                summary.failed(), degraded, elapsedMs);
    }

    private Tweet minimalTweet(String tweetId, String normalized) {
        return Tweet.builder().id(tweetId).url(normalized).build();
    }

    private boolean isProcessable(Tweet tweet) {
        return tweet != null && (StringUtils.hasText(tweet.getContent())
                || StringUtils.hasText(tweet.getRawText())
                || StringUtils.hasText(tweet.getFormattedText())
                || (tweet.getMedia() != null && !tweet.getMedia().isEmpty()));
    }

    /** 可公开返回的脱敏执行摘要。 */
    public record Result(String tweetId, int discovered, int filtered, int archived,
                         int failed, boolean degraded, long elapsedMs) {
    }
}
