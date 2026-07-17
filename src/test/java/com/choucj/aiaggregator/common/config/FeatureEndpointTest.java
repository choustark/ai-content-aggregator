package com.choucj.aiaggregator.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 5.1 AC5 — {@link FeatureEndpoint} 暴露 rag.enabled 与批量异步开关.
 */
class FeatureEndpointTest {

    @Test
    void shouldExposeRagFlags() {
        FeatureFlagsProperties properties = new FeatureFlagsProperties();
        properties.getRag().setEnabled(true);
        properties.getRag().setBatchAsyncEnabled(true);
        MockEnvironment env = new MockEnvironment()
                .withProperty("features.rag.enabled", "true")
                .withProperty("features.github.enabled", "false");

        FeatureEndpoint endpoint = new FeatureEndpoint(properties, env);

        Map<String, Object> snapshot = endpoint.features();
        @SuppressWarnings("unchecked")
        Map<String, Object> rag = (Map<String, Object>) snapshot.get("rag");
        assertThat(rag)
                .containsEntry("enabled", true)
                .containsEntry("featureFlagEnabled", true)
                .containsEntry("batchAsyncEnabled", true);
    }

    @Test
    void shouldDefaultRagEndpointFlagToFalse() {
        FeatureEndpoint endpoint = new FeatureEndpoint(new FeatureFlagsProperties(), new StandardEnvironment());

        @SuppressWarnings("unchecked")
        Map<String, Object> rag = (Map<String, Object>) endpoint.features().get("rag");
        assertThat(rag).containsEntry("enabled", false);
    }

    @Test
    void shouldDefaultGithubEndpointFlagToFeatureFlagValue() {
        FeatureFlagsProperties properties = new FeatureFlagsProperties();
        FeatureEndpoint endpoint = new FeatureEndpoint(properties, new StandardEnvironment());

        @SuppressWarnings("unchecked")
        Map<String, Object> github = (Map<String, Object>) endpoint.features().get("github");
        assertThat(github)
                .containsEntry("enabled", false)
                .containsEntry("featureFlagEnabled", false);
    }

    @Test
    void shouldExposeMultiModelFlagFromFeatureFlags() {
        FeatureFlagsProperties properties = new FeatureFlagsProperties();
        properties.getMultiModel().setEnabled(true);
        FeatureEndpoint endpoint = new FeatureEndpoint(properties, new StandardEnvironment());

        @SuppressWarnings("unchecked")
        Map<String, Object> multiModel = (Map<String, Object>) endpoint.features().get("multiModel");
        assertThat(multiModel).containsEntry("enabled", true);
    }
}
