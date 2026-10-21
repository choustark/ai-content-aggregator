package com.choucj.aiaggregator.publish.wechat.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 微信正文视频节点的测试专用、保守型结构检查器。 */
final class InlineVideoNodeInspector {

    private static final Pattern TAG = Pattern.compile("<([A-Za-z][A-Za-z0-9:-]*)(\\s[^<>]*?)?/?>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)((?:access[_-]?token|cookie|secret)\\s*=\\s*[\"']?)([^&\\s\"']+)");
    private static final Pattern MEDIA_ID = Pattern.compile("(?i)(media[_-]?id[=:\\s]+)([A-Za-z0-9_-]{9,})");
    private static final Pattern DATA_MPVID = Pattern.compile("(?i)(data-mpvid\\s*=\\s*[\"']?)([A-Za-z0-9_-]{5,})");
    private static final Pattern VID_QUERY = Pattern.compile("(?i)([?&]vid=)([A-Za-z0-9_-]{5,})");
    private static final Pattern WXV_ID = Pattern.compile("(?i)wxv_[A-Za-z0-9_-]{5,}");
    private static final Set<String> IMPORTANT = Set.of("src", "poster", "cover", "width", "height", "ratio", "id", "class");

    private InlineVideoNodeInspector() {
    }

    static List<Node> extract(String html) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("正文不能为空");
        }
        List<Node> result = new ArrayList<>();
        Matcher tags = TAG.matcher(html);
        int offset = 0;
        while (tags.find(offset)) {
            String tag = tags.group(1).toLowerCase(Locale.ROOT);
            Map<String, String> attributes = attributes(tags.group(2));
            if (isPlayer(tag, attributes)) {
                String opening = tags.group();
                if (opening.matches("(?s).*?/\\s*>")) {
                    result.add(new Node(tag, attributes, opening));
                    offset = tags.end();
                    continue;
                }
                Matcher closing = Pattern.compile("(?i)</\\s*" + Pattern.quote(tag) + "\\s*>")
                        .matcher(html);
                if (closing.find(tags.end())) {
                    result.add(new Node(tag, attributes, html.substring(tags.start(), closing.end())));
                    offset = closing.end();
                    continue;
                }
            }
            offset = tags.end();
        }
        return List.copyOf(result);
    }

    static Comparison compare(List<Node> source, List<Node> stored) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("源正文没有有效视频节点");
        }
        if (stored == null || stored.size() < source.size()) {
            return new Comparison(false, List.of("回读视频节点数量不足: source=" + source.size()
                    + ", stored=" + (stored == null ? 0 : stored.size())));
        }
        List<String> differences = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            int index = i;
            Node expected = source.get(i);
            Node actual = stored.get(i);
            if (!expected.tag().equals(actual.tag())) {
                differences.add("节点[" + i + "]标签改写: " + expected.tag() + " -> " + actual.tag());
            }
            expected.criticalAttributes().forEach((name, value) -> {
                String actualValue = actual.attributes().get(name);
                if (actualValue == null) {
                    differences.add("节点[" + index + "]关键属性丢失: " + name);
                } else if (!value.equals(actualValue)) {
                    differences.add("节点[" + index + "]关键属性改写: " + name);
                }
            });
        }
        return new Comparison(differences.isEmpty(), List.copyOf(differences));
    }

    static String redact(String text) {
        if (text == null) {
            return null;
        }
        String redacted = SENSITIVE.matcher(text).replaceAll("$1<redacted>");
        redacted = MEDIA_ID.matcher(redacted).replaceAll(match -> match.group(1) + prefix(match.group(2)));
        redacted = DATA_MPVID.matcher(redacted).replaceAll(match -> match.group(1) + prefix(match.group(2)));
        redacted = VID_QUERY.matcher(redacted).replaceAll(match -> match.group(1) + prefix(match.group(2)));
        return WXV_ID.matcher(redacted).replaceAll(match -> prefix(match.group()));
    }

    private static Map<String, String> attributes(String raw) {
        Map<String, String> result = new TreeMap<>();
        if (raw == null) {
            return result;
        }
        Matcher matcher = ATTR.matcher(raw);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            result.put(matcher.group(1).toLowerCase(Locale.ROOT), value);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static boolean isPlayer(String tag, Map<String, String> attributes) {
        if ("video".equals(tag)) {
            return true;
        }
        String joined = tag + " " + attributes;
        return joined.contains("video_iframe") || joined.contains("wxv_")
                || joined.toLowerCase(Locale.ROOT).contains("mpvid");
    }

    private static boolean critical(String name, String value) {
        String lowerValue = value.toLowerCase(Locale.ROOT);
        return IMPORTANT.contains(name) || name.startsWith("data-")
                || lowerValue.contains("wxv_") || lowerValue.contains("mpvid");
    }

    private static String prefix(String value) {
        return value.substring(0, Math.min(8, value.length())) + "...<redacted>";
    }

    record Node(String tag, Map<String, String> attributes, String raw) {
        Node {
            attributes = Map.copyOf(attributes);
        }

        Map<String, String> criticalAttributes() {
            Map<String, String> result = new TreeMap<>();
            attributes.forEach((name, value) -> {
                if (critical(name, value)) {
                    result.put(name, value);
                }
            });
            return Collections.unmodifiableMap(result);
        }

        String redactedSnapshot() {
            return redact(raw);
        }
    }

    record Comparison(boolean matches, List<String> differences) {
        Comparison {
            differences = List.copyOf(differences);
        }
    }
}
