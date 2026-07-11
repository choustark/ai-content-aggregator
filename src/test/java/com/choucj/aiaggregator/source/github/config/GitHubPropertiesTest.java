package com.choucj.aiaggregator.source.github.config;

import com.choucj.aiaggregator.source.github.config.GitHubProperties.Readme;
import com.choucj.aiaggregator.source.github.config.GitHubProperties.Trending;
import com.choucj.aiaggregator.source.github.config.GitHubProperties.ValueAnalyzer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 4.1 {@link GitHubProperties} 配置绑定 + 校验测试.
 *
 * <p>覆盖默认值 / 自定义值 / @Min @Max 边界 (trending.lookback-days / top-n / readme.max-size-kb).
 */
class GitHubPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setupValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void shouldUseDefaultsWhenUnset() {
        GitHubProperties p = new GitHubProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getToken()).isEmpty();
        assertThat(p.getTimeoutSeconds()).isEqualTo(30);

        Trending t = p.getTrending();
        assertThat(t.getQueryTemplate()).isEqualTo("created:>{date}+language:{language}");
        assertThat(t.getLookbackDays()).isEqualTo(7);
        assertThat(t.getTopN()).isEqualTo(10);
        assertThat(t.getLanguage()).isEqualTo("java");

        Readme r = p.getReadme();
        assertThat(r.getMaxSizeKb()).isEqualTo(100);
    }

    @Test
    void shouldBindConfiguredValues() {
        GitHubProperties p = new GitHubProperties();
        p.setEnabled(false);
        p.setToken("github_pat_test");
        p.setTimeoutSeconds(60);

        Trending t = new Trending();
        t.setQueryTemplate("created:>{date}+stars:>100");
        t.setLookbackDays(30);
        t.setTopN(50);
        t.setLanguage("python");
        p.setTrending(t);

        Readme r = new Readme();
        r.setMaxSizeKb(200);
        p.setReadme(r);

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getToken()).isEqualTo("github_pat_test");
        assertThat(p.getTimeoutSeconds()).isEqualTo(60);
        assertThat(p.getTrending().getLookbackDays()).isEqualTo(30);
        assertThat(p.getTrending().getTopN()).isEqualTo(50);
        assertThat(p.getTrending().getLanguage()).isEqualTo("python");
        assertThat(p.getReadme().getMaxSizeKb()).isEqualTo(200);
    }

    @Test
    void shouldRejectNegativeTimeoutSeconds() {
        GitHubProperties p = new GitHubProperties();
        p.setTimeoutSeconds(0);
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("timeoutSeconds"));

        p.setTimeoutSeconds(301);
        violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("timeoutSeconds"));
    }

    @Test
    void shouldRejectLookbackDaysOutOfRange() {
        GitHubProperties p = new GitHubProperties();
        p.getTrending().setLookbackDays(0);
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("trending.lookbackDays"));

        p.getTrending().setLookbackDays(91);
        violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("trending.lookbackDays"));
    }

    @Test
    void shouldRejectTopNOutOfRange() {
        GitHubProperties p = new GitHubProperties();
        p.getTrending().setTopN(0);
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("trending.topN"));

        p.getTrending().setTopN(101);
        violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("trending.topN"));
    }

    @Test
    void shouldRejectReadmeMaxSizeOutOfRange() {
        GitHubProperties p = new GitHubProperties();
        p.getReadme().setMaxSizeKb(0);
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("readme.maxSizeKb"));

        p.getReadme().setMaxSizeKb(1025);
        violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("readme.maxSizeKb"));
    }

    @Test
    void shouldRejectNullNestedProperties() {
        GitHubProperties p = new GitHubProperties();
        p.setTrending(null);
        p.setReadme(null);

        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("trending"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("readme"));
    }

    @Test
    void shouldRejectBlankQueryTemplateAndLanguage() {
        GitHubProperties p = new GitHubProperties();
        p.getTrending().setQueryTemplate(" ");
        p.getTrending().setLanguage("");

        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("trending.queryTemplate"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("trending.language"));
    }

    // ---------- Story 4.3 ValueAnalyzer 配置绑定 + 校验 ----------

    @Test
    void shouldAcceptDefaultValueAnalyzer() {
        GitHubProperties p = new GitHubProperties();
        ValueAnalyzer va = p.getValueAnalyzer();
        assertThat(va).isNotNull();
        assertThat(va.getScoreThreshold()).isEqualTo(7.0);
        assertThat(va.getStarFallbackThreshold()).isEqualTo(100);
        assertThat(va.getReadmeMaxCodePoints()).isEqualTo(8000);

        // 默认值校验应通过
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().startsWith("valueAnalyzer"));
    }

    @Test
    void shouldBindConfiguredValueAnalyzer() {
        GitHubProperties p = new GitHubProperties();
        ValueAnalyzer va = new ValueAnalyzer();
        va.setScoreThreshold(8.5);
        va.setStarFallbackThreshold(500);
        va.setReadmeMaxCodePoints(16000);
        p.setValueAnalyzer(va);

        assertThat(p.getValueAnalyzer().getScoreThreshold()).isEqualTo(8.5);
        assertThat(p.getValueAnalyzer().getStarFallbackThreshold()).isEqualTo(500);
        assertThat(p.getValueAnalyzer().getReadmeMaxCodePoints()).isEqualTo(16000);
    }

    @Test
    void shouldRejectScoreThresholdNegative() {
        GitHubProperties p = new GitHubProperties();
        p.getValueAnalyzer().setScoreThreshold(-0.1);
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("valueAnalyzer.scoreThreshold"));
    }

    @Test
    void shouldRejectScoreThresholdOver10() {
        GitHubProperties p = new GitHubProperties();
        p.getValueAnalyzer().setScoreThreshold(10.1);
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("valueAnalyzer.scoreThreshold"));
    }

    @Test
    void shouldRejectStarFallbackNegative() {
        GitHubProperties p = new GitHubProperties();
        p.getValueAnalyzer().setStarFallbackThreshold(-1);
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("valueAnalyzer.starFallbackThreshold"));
    }

    @Test
    void shouldRejectStarFallbackOverLimit() {
        GitHubProperties p = new GitHubProperties();
        p.getValueAnalyzer().setStarFallbackThreshold(1_000_001);
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("valueAnalyzer.starFallbackThreshold"));
    }

    @Test
    void shouldRejectReadmeMaxCodePointsUnder1000() {
        GitHubProperties p = new GitHubProperties();
        p.getValueAnalyzer().setReadmeMaxCodePoints(999);
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("valueAnalyzer.readmeMaxCodePoints"));
    }

    @Test
    void shouldRejectReadmeMaxCodePointsOver50K() {
        GitHubProperties p = new GitHubProperties();
        p.getValueAnalyzer().setReadmeMaxCodePoints(50_001);
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("valueAnalyzer.readmeMaxCodePoints"));
    }

    @Test
    void shouldRejectNullValueAnalyzer() {
        GitHubProperties p = new GitHubProperties();
        p.setValueAnalyzer(null);
        Set<ConstraintViolation<GitHubProperties>> violations = validator.validate(p);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("valueAnalyzer"));
    }
}
