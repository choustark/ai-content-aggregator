package com.choucj.aiaggregator.source.twitter;

import com.choucj.aiaggregator.common.exception.DegradationException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.processor.TwitterProcessor;
import com.choucj.aiaggregator.source.twitter.discovery.XAuthorScraperDiscoveryClient;
import com.choucj.aiaggregator.source.twitter.discovery.XPostTargetNormalizer;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Arrays;
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

    @Mock private XPostTargetNormalizer normalizer;
    @Mock private XAuthorScraperDiscoveryClient scraper;
    @Mock private TwitterSource twitterSource;
    @Mock private TwitterProcessor processor;
    private SpecifiedXContentService service;

    @BeforeEach
    void setUp() {
        service = new SpecifiedXContentService(normalizer, scraper, twitterSource, processor);
    }

    @Test
    void shouldRejectNonXTargetWithoutExternalCalls() {
        when(normalizer.normalize("https://example.com/status/123")).thenReturn(null);

        assertThatThrownBy(() -> service.process("https://example.com/status/123"))
                .isInstanceOf(NonRetryableException.class);

        verify(scraper, never()).discoverSpecifiedPost(org.mockito.ArgumentMatchers.any());
        verify(twitterSource, never()).enrichSpecifiedTweets(anyList());
        verify(processor, never()).processTweets(anyList());
    }

    @Test
    void shouldProcessOnlyRequestedPostAndReturnSafeCounts() {
        String normalized = "https://x.com/a/status/123";
        Tweet requested = Tweet.builder().id("123").content("secret body").url(normalized).build();
        Tweet unrelated = Tweet.builder().id("999").content("other body").build();
        XPostTargetNormalizer.Target parsed = new XPostTargetNormalizer.Target(normalized, "123");
        when(normalizer.normalize(normalized)).thenReturn(parsed);
        when(scraper.discoverSpecifiedPost(parsed)).thenReturn(List.of(unrelated, requested));
        when(processor.processTweets(List.of(requested)))
                .thenReturn(new TwitterProcessor.ProcessingSummary(7, 5, 3, 2));

        SpecifiedXContentService.Result result = service.process(normalized);

        assertThat(result.tweetId()).isEqualTo("123");
        assertThat(result.discovered()).isEqualTo(7);
        assertThat(result.filtered()).isEqualTo(5);
        assertThat(result.archived()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.degraded()).isFalse();
        verify(twitterSource, never()).enrichSpecifiedTweets(anyList());
        verify(processor).processTweets(List.of(requested));
    }

    @Test
    void shouldFallbackToExistingCompletionChainWhenPrimaryFails() {
        String normalized = "https://x.com/i/status/123";
        Tweet enriched = Tweet.builder().id("123").content("fallback body").url(normalized).build();
        XPostTargetNormalizer.Target parsed = new XPostTargetNormalizer.Target(normalized, "123");
        when(normalizer.normalize("123")).thenReturn(parsed);
        when(scraper.discoverSpecifiedPost(parsed))
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
        XPostTargetNormalizer.Target parsed = new XPostTargetNormalizer.Target(normalized, "123");
        when(normalizer.normalize("123")).thenReturn(parsed);
        when(scraper.discoverSpecifiedPost(parsed))
                .thenThrow(new NonRetryableException("invalid actor input"));

        assertThatThrownBy(() -> service.process("123"))
                .isInstanceOf(NonRetryableException.class);

        verify(twitterSource, never()).enrichSpecifiedTweets(anyList());
    }

    @Test
    void shouldNotCompletePermanentSessionOrRateLimitFailures() {
        String normalized = "https://x.com/i/status/123";
        XPostTargetNormalizer.Target parsed = new XPostTargetNormalizer.Target(normalized, "123");
        when(normalizer.normalize("123")).thenReturn(parsed);
        when(scraper.discoverSpecifiedPost(parsed))
                .thenThrow(new NonRetryableException("LOGIN_REQUIRED"))
                .thenThrow(new NonRetryableException("RATE_LIMITED"));

        assertThatThrownBy(() -> service.process("123"))
                .isInstanceOf(NonRetryableException.class).hasMessageContaining("LOGIN_REQUIRED");
        assertThatThrownBy(() -> service.process("123"))
                .isInstanceOf(NonRetryableException.class).hasMessageContaining("RATE_LIMITED");
        verify(twitterSource, never()).enrichSpecifiedTweets(anyList());
        verify(processor, never()).processTweets(anyList());
    }

    @Test
    void shouldUseExactlyOneNonCloudCompletionForTechnicalJobFailures() {
        String normalized = "https://x.com/i/status/123";
        XPostTargetNormalizer.Target parsed = new XPostTargetNormalizer.Target(normalized, "123");
        Tweet enriched = Tweet.builder().id("123").content("fallback body").url(normalized).build();
        when(normalizer.normalize("123")).thenReturn(parsed);
        when(scraper.discoverSpecifiedPost(parsed))
                .thenThrow(new RetryableException("JOB_NOT_FOUND"))
                .thenThrow(new RetryableException("CANCELLED"))
                .thenThrow(new RetryableException("ACTOR_FAILED"))
                .thenThrow(new RetryableException("INVALID_STATUS"))
                .thenThrow(new RetryableException("INVALID_RESULT_STRUCTURE"));
        when(twitterSource.enrichSpecifiedTweets(anyList())).thenReturn(List.of(enriched));
        when(processor.processTweets(List.of(enriched)))
                .thenReturn(new TwitterProcessor.ProcessingSummary(1, 1, 1, 0));

        assertThat(service.process("123").degraded()).isTrue();
        assertThat(service.process("123").degraded()).isTrue();
        assertThat(service.process("123").degraded()).isTrue();
        assertThat(service.process("123").degraded()).isTrue();
        assertThat(service.process("123").degraded()).isTrue();

        verify(scraper, times(5)).discoverSpecifiedPost(parsed);
        verify(twitterSource, times(5)).enrichSpecifiedTweets(anyList());
        verify(processor, times(5)).processTweets(List.of(enriched));
    }

    @Test
    void shouldHaveNoApifyDependencyInConstructionOrFields() {
        assertThat(Arrays.stream(SpecifiedXContentService.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                .noneMatch(type -> type.getName().endsWith("ApifyDiscoveryClient"));
        assertThat(Arrays.stream(SpecifiedXContentService.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .noneMatch(name -> name.endsWith("ApifyDiscoveryClient"));
    }

    @Test
    void shouldFailClosedWithoutCompletionWhenPrimaryResultIsEmpty() {
        String normalized = "https://x.com/i/status/123";
        XPostTargetNormalizer.Target parsed = new XPostTargetNormalizer.Target(normalized, "123");
        when(normalizer.normalize("123")).thenReturn(parsed);
        when(scraper.discoverSpecifiedPost(parsed)).thenReturn(List.of());

        assertThatThrownBy(() -> service.process("123"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("POST_NOT_FOUND");
        verify(twitterSource, never()).enrichSpecifiedTweets(anyList());
    }

    @Test
    void shouldProcessMediaOnlyLocalResultWithoutCompletion() {
        String normalized = "https://x.com/i/status/123";
        Tweet selected = Tweet.builder().id("123").url(normalized)
                .media(List.of(com.choucj.aiaggregator.source.twitter.model.TweetMedia.builder()
                        .sourceUrl("https://media.example/image.jpg").build())).build();
        XPostTargetNormalizer.Target parsed = new XPostTargetNormalizer.Target(normalized, "123");
        when(normalizer.normalize("123")).thenReturn(parsed);
        when(scraper.discoverSpecifiedPost(parsed)).thenReturn(List.of(selected));
        when(processor.processTweets(List.of(selected)))
                .thenReturn(new TwitterProcessor.ProcessingSummary(1, 1, 1, 0));

        assertThat(service.process("123").degraded()).isFalse();

        verify(twitterSource, never()).enrichSpecifiedTweets(anyList());
        verify(processor).processTweets(List.of(selected));
    }

    @Test
    void shouldSerializeConcurrentRequestsBeforeEnteringDiscovery() throws Exception {
        String normalized = "https://x.com/i/status/123";
        Tweet tweet = Tweet.builder().id("123").content("body").url(normalized).build();
        CountDownLatch firstEnteredProcessor = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        XPostTargetNormalizer.Target parsed = new XPostTargetNormalizer.Target(normalized, "123");
        when(normalizer.normalize("123")).thenReturn(parsed);
        when(scraper.discoverSpecifiedPost(parsed)).thenReturn(List.of(tweet));
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
        verify(scraper, times(1)).discoverSpecifiedPost(parsed);
        releaseFirst.countDown();

        assertThat(first.get(2, TimeUnit.SECONDS).tweetId()).isEqualTo("123");
        assertThat(second.get(2, TimeUnit.SECONDS).tweetId()).isEqualTo("123");
        verify(scraper, times(2)).discoverSpecifiedPost(parsed);
    }

    @Test
    void shouldKeepDeterministicTweetIdentityAcrossRepeatedRequests() {
        String normalized = "https://x.com/i/status/123";
        Tweet tweet = Tweet.builder().id("123").content("body").url(normalized).build();
        XPostTargetNormalizer.Target parsed = new XPostTargetNormalizer.Target(normalized, "123");
        when(normalizer.normalize("123")).thenReturn(parsed);
        when(scraper.discoverSpecifiedPost(parsed)).thenReturn(List.of(tweet));
        when(processor.processTweets(List.of(tweet)))
                .thenReturn(new TwitterProcessor.ProcessingSummary(1, 1, 1, 0));

        service.process("123");
        service.process("123");

        verify(processor, times(2)).processTweets(List.of(tweet));
    }

    @Test
    void shouldFailWithExistingDegradationContractWhenAllPathsFail() {
        String normalized = "https://x.com/i/status/123";
        XPostTargetNormalizer.Target parsed = new XPostTargetNormalizer.Target(normalized, "123");
        when(normalizer.normalize("123")).thenReturn(parsed);
        when(scraper.discoverSpecifiedPost(parsed))
                .thenThrow(new DegradationException("quota"));
        when(twitterSource.enrichSpecifiedTweets(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> service.process("123"))
                .isInstanceOf(DegradationException.class);

        verify(processor, never()).processTweets(anyList());
    }
}
