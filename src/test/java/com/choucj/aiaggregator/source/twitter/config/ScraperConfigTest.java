package com.choucj.aiaggregator.source.twitter.config;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ScraperConfigTest {

    @Test
    void shouldOmitAuthorizationWhenApiKeyIsBlank() {
        verifyAuthorization(null, false);
        verifyAuthorization("   ", false);
    }

    @Test
    void shouldSendTrimmedBearerAuthorizationWhenApiKeyIsConfigured() {
        verifyAuthorization("  secret-key  ", true);
    }

    private void verifyAuthorization(String apiKey, boolean expected) {
        ScraperProperties properties = new ScraperProperties();
        properties.setApiKey(apiKey);
        HttpServer server = null;
        try {
            AtomicReference<String> authorization = new AtomicReference<>();
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/ping", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
            RestClient client = new ScraperConfig().xAuthorScraperRestClient(properties);
            client.get().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/ping")
                    .retrieve().toBodilessEntity();
            if (expected) {
                assertThat(authorization.get()).isEqualTo("Bearer secret-key");
            } else {
                assertThat(authorization.get()).isNull();
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }
}
