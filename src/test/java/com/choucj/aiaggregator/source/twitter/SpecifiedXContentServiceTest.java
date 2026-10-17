package com.choucj.aiaggregator.source.twitter;

import com.choucj.aiaggregator.common.exception.DegradationException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.processor.TwitterProcessor;
import com.choucj.aiaggregator.source.twitter.discovery.ApifyDiscoveryClient;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecifiedXContentServiceTest {

    @Mock private ApifyDiscoveryClient apify;
    @Mock private TwitterSource twitterSource;
    @Mock private TwitterProcessor processor;
    private SpecifiedXContentService service;

    @BeforeEach
    void setUp() {
        service = new SpecifiedXContentService(apify, twitterSource, processor);
    }

    @Test
    void shouldRejectNonXTargetWithoutExternalCalls() {
        when(apify.normalizeTarget("https://example.com/status/123")).thenReturn(null);

        assertThatThrownBy(() -> service.process("https://example.com/status/123"))
                .isInstanceOf(NonRetryableException.class);

        verify(apify, never()).discoverSpecifiedTweets(anyList());
        verify(twitterSource, never()).enrichSpecifiedTweets(anyList());
        verify(processor, never()).processTweets(anyList());
    }

    @Test
    void shouldProcessOnlyRequestedPostAndReturnSafeCounts() {
        String normalized = "https://x.com/a/status/123";
        Tweet requested = Tweet.builder().id("123").content("secret body").url(normalized).build();
        Tweet unrelated = Tweet.builder().id("999").content("other body").build();
        when(apify.normalizeTarget(normalized)).thenReturn(normalized);
        when(apify.discoverSpecifiedTweets(List.of(normalized))).thenReturn(List.of(unrelated, requested));
        when(twitterSource.enrichSpecifiedTweets(List.of(requested))).thenReturn(List.of(requested));
        when(processor.processTweets(List.of(requested)))
                .thenReturn(new TwitterProcessor.ProcessingSummary(7, 5, 3, 2));

        SpecifiedXContentService.Result result = service.process(normalized);

        assertThat(result.tweetId()).isEqualTo("123");
        assertThat(result.discovered()).isEqualTo(7);
        assertThat(result.filtered()).isEqualTo(5);
        assertThat(result.archived()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.degraded()).isFalse();
        verify(processor).processTweets(List.of(requested));
    }

    @Test
    void shouldFallbackToExistingCompletionChainWhenPrimaryFails() {
        String normalized = "https://x.com/i/status/123";
        Tweet enriched = Tweet.builder().id("123").content("fallback body").url(normalized).build();
        when(apify.normalizeTarget("123")).thenReturn(normalized);
        when(apify.discoverSpecifiedTweets(List.of(normalized)))
                .thenThrow(new DegradationException("quota"));
        when(twitterSource.enrichSpecifiedTweets(anyList())).thenReturn(List.of(enriched));
        when(processor.processTweets(List.of(enriched)))
                .thenReturn(new TwitterProcessor.ProcessingSummary(1, 1, 1, 0));

        SpecifiedXContentService.Result result = service.process("123");

        assertThat(result.degraded()).isTrue();
        assertThat(result.tweetId()).isEqualTo("123");
    }

    @Test
    void shouldPropagatePermanentDiscoveryFailure() {
        String normalized = "https://x.com/i/status/123";
        when(apify.normalizeTarget("123")).thenReturn(normalized);
        when(apify.discoverSpecifiedTweets(List.of(normalized)))
                .thenThrow(new NonRetryableException("invalid actor input"));

        assertThatThrownBy(() -> service.process("123"))
                .isInstanceOf(NonRetryableException.class);

        verify(twitterSource, never()).enrichSpecifiedTweets(anyList());
    }

    @Test
    void shouldMarkEmptyPrimaryResultAsDegraded() {
        String normalized = "https://x.com/i/status/123";
        Tweet enriched = Tweet.builder().id("123").content("fallback body").url(normalized).build();
        when(apify.normalizeTarget("123")).thenReturn(normalized);
        when(apify.discoverSpecifiedTweets(List.of(normalized))).thenReturn(List.of());
        when(twitterSource.enrichSpecifiedTweets(anyList())).thenReturn(List.of(enriched));
        when(processor.processTweets(List.of(enriched)))
                .thenReturn(new TwitterProcessor.ProcessingSummary(1, 1, 1, 0));

        assertThat(service.process("123").degraded()).isTrue();
    }

    @Test
    void shouldRejectMismatchedEnrichmentIdentity() {
        String normalized = "https://x.com/i/status/123";
        Tweet selected = Tweet.builder().id("123").url(normalized).build();
        Tweet wrong = Tweet.builder().id("999").content("wrong body").build();
        when(apify.normalizeTarget("123")).thenReturn(normalized);
        when(apify.discoverSpecifiedTweets(List.of(normalized))).thenReturn(List.of(selected));
        when(twitterSource.enrichSpecifiedTweets(List.of(selected))).thenReturn(List.of(wrong));

        assertThatThrownBy(() -> service.process("123"))
                .isInstanceOf(DegradationException.class);

        verify(processor, never()).processTweets(anyList());
    }

    @Test
    void shouldSerializeConcurrentRequestsBeforeEnteringDiscovery() throws Exception {
        String normalized = "https://x.com/i/status/123";
        Tweet tweet = Tweet.builder().id("123").content("body").url(normalized).build();
        CountDownLatch firstEnteredProcessor = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        when(apify.normalizeTarget("123")).thenReturn(normalized);
        when(apify.discoverSpecifiedTweets(List.of(normalized))).thenReturn(List.of(tweet));
        when(twitterSource.enrichSpecifiedTweets(List.of(tweet))).thenReturn(List.of(tweet));
        when(processor.processTweets(List.of(tweet))).thenAnswer(invocation -> {
            firstEnteredProcessor.countDown();
            if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                throw new RetryableException("test timeout");
            }
            return new TwitterProcessor.ProcessingSummary(1, 1, 1, 0);
        });

        CompletableFuture<SpecifiedXContentService.Result> first =
                CompletableFuture.supplyAsync(() -> service.process("123"));
        assertThat(firstEnteredProcessor.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<SpecifiedXContentService.Result> second =
                CompletableFuture.supplyAsync(() -> {
                    secondAttempted.countDown();
                    return service.process("123");
                });

        assertThat(secondAttempted.await(2, TimeUnit.SECONDS)).isTrue();
        verify(apify, times(1)).discoverSpecifiedTweets(List.of(normalized));
        releaseFirst.countDown();

        assertThat(first.get(2, TimeUnit.SECONDS).tweetId()).isEqualTo("123");
        assertThat(second.get(2, TimeUnit.SECONDS).tweetId()).isEqualTo("123");
        verify(apify, times(2)).discoverSpecifiedTweets(List.of(normalized));
    }

    @Test
    void shouldKeepDeterministicTweetIdentityAcrossRepeatedRequests() {
        String normalized = "https://x.com/i/status/123";
        Tweet tweet = Tweet.builder().id("123").content("body").url(normalized).build();
        when(apify.normalizeTarget("123")).thenReturn(normalized);
        when(apify.discoverSpecifiedTweets(List.of(normalized))).thenReturn(List.of(tweet));
        when(twitterSource.enrichSpecifiedTweets(List.of(tweet))).thenReturn(List.of(tweet));
        when(processor.processTweets(List.of(tweet)))
                .thenReturn(new TwitterProcessor.ProcessingSummary(1, 1, 1, 0));

        service.process("123");
        service.process("123");

        verify(processor, times(2)).processTweets(List.of(tweet));
    }

    @Test
    void shouldFailWithExistingDegradationContractWhenAllPathsFail() {
        String normalized = "https://x.com/i/status/123";
        when(apify.normalizeTarget("123")).thenReturn(normalized);
        when(apify.discoverSpecifiedTweets(List.of(normalized)))
                .thenThrow(new DegradationException("quota"));
        when(twitterSource.enrichSpecifiedTweets(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> service.process("123"))
                .isInstanceOf(DegradationException.class);

        verify(processor, never()).processTweets(anyList());
    }
}
