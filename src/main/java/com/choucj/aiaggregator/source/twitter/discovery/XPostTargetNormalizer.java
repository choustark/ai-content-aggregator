package com.choucj.aiaggregator.source.twitter.discovery;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;

/** 指定 X Post 目标的 provider 无关校验、标准化与 ID 提取。 */
@Component
public class XPostTargetNormalizer {

    public Target normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        if (value.matches("\\d{1,20}")) {
            String id = value.replaceFirst("^0+(?!$)", "");
            return new Target("https://x.com/i/status/" + id, id);
        }
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !allowedHost(uri.getHost()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || !StringUtils.hasText(uri.getPath())) {
                return null;
            }
            String path = uri.getPath();
            if (!path.matches("/(?:[^/]+|i)/status/\\d{1,20}/?")) {
                return null;
            }
            String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            String id = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
            return new Target("https://x.com" + normalizedPath, id);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public String normalizeUrl(String raw) {
        Target target = normalize(raw);
        return target == null ? null : target.url();
    }

    private boolean allowedHost(String host) {
        if (!StringUtils.hasText(host)) {
            return false;
        }
        return switch (host.toLowerCase()) {
            case "x.com", "www.x.com", "twitter.com", "www.twitter.com" -> true;
            default -> false;
        };
    }

    public record Target(String url, String tweetId) {
    }
}
