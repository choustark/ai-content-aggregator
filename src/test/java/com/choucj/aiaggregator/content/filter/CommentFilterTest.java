package com.choucj.aiaggregator.content.filter;

import com.choucj.aiaggregator.content.filter.config.FilterProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 2.3b {@link CommentFilter} 单测 — 评论数筛选行为.
 */
class CommentFilterTest {

    private CommentFilter filter;

    @BeforeEach
    void setUp() {
        FilterProperties props = new FilterProperties();
        props.setCommentThreshold(10);
        filter = new CommentFilter(props);
    }

    private Tweet tweet(String id, int replyCount) {
        return Tweet.builder().id(id).replyCount(replyCount).build();
    }

    @Test
    void shouldReturnEmptyListWhenInputEmpty() {
        assertThat(filter.filter(Collections.emptyList())).isEmpty();
        assertThat(filter.filter(null)).isEmpty();
    }

    @Test
    void shouldKeepAllWhenAllAboveThreshold() {
        List<Tweet> input = List.of(tweet("a", 10), tweet("b", 100));
        assertThat(filter.filter(input)).hasSize(2);
    }

    @Test
    void shouldFilterOutAllWhenAllBelowThreshold() {
        List<Tweet> input = List.of(tweet("a", 0), tweet("b", 9));
        assertThat(filter.filter(input)).isEmpty();
    }

    @Test
    void shouldKeepOrderAndOnlyPassAboveThreshold() {
        List<Tweet> input = List.of(
                tweet("a", 5),
                tweet("b", 10),
                tweet("c", 3),
                tweet("d", 50));
        List<Tweet> result = filter.filter(input);
        assertThat(result).extracting(Tweet::getId).containsExactly("b", "d");
    }

    @Test
    void shouldNotModifyInputList() {
        List<Tweet> input = new java.util.ArrayList<>(List.of(tweet("a", 5), tweet("b", 100)));
        filter.filter(input);
        assertThat(input).hasSize(2);
    }
}
