package com.choucj.aiaggregator.publish.wechat.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Story 10.13 测试专用标识对账器；不进入生产代码。 */
final class InlineVideoIdentityMappingInspector {

    private static final Pattern WXV = Pattern.compile("(?i)wxv_[A-Za-z0-9_-]{5,}");
    private static final Pattern MPVID = Pattern.compile(
            "(?i)(?:data-mpvid\\s*=\\s*[\"']?|[?&]vid=)([A-Za-z0-9_-]{5,})");

    private InlineVideoIdentityMappingInspector() {
    }

    static Result inspect(PublicMaterialFields material, List<InlineVideoNodeInspector.Node> nodes,
                          MappingEvidence evidence) {
        if (evidence != null && hasText(evidence.environmentBlocker())) {
            return result(Decision.ENVIRONMENT_BLOCKED, material, nodes,
                    "环境前置不完整: " + evidence.environmentBlocker());
        }
        if (evidence != null && evidence.privateLoginDependency()) {
            return result(Decision.NO_GO, material, nodes,
                    "映射仅依赖私有登录态后台路径");
        }
        if (material == null) {
            return result(Decision.ENVIRONMENT_BLOCKED, null, nodes, "公开素材回读缺失");
        }

        Set<String> nodeIds = nodeIdentities(nodes);
        if (nodeIds.isEmpty()) {
            return result(Decision.ENVIRONMENT_BLOCKED, material, nodes, "源草稿缺少播放器节点标识");
        }
        boolean directPublicMatch = evidence != null && evidence.documentedPublicField()
                && evidence.explicitAssociatedIds() != null
                && evidence.explicitAssociatedIds().stream().anyMatch(nodeIds::contains);
        if (directPublicMatch && evidence.repeatedlyVerified()) {
            return result(Decision.GO, material, nodes,
                    "公开文档化字段直接关联节点标识，且已重复验证");
        }
        if (directPublicMatch) {
            return result(Decision.CONDITIONAL_GO, material, nodes,
                    "存在公开显式关联，但尚未完成重复验证");
        }
        return result(Decision.CONDITIONAL_GO, material, nodes,
                "公开素材字段未提供 materialMediaId 到 wxvId/mpvid 的显式关联");
    }

    private static Result result(Decision decision, PublicMaterialFields material,
                                 List<InlineVideoNodeInspector.Node> nodes, String reason) {
        Map<String, String> matrix = new LinkedHashMap<>();
        matrix.put("materialMediaId", summary(material == null ? null : material.materialMediaId()));
        matrix.put("title", presence(material == null ? null : material.title()));
        matrix.put("description", presence(material == null ? null : material.description()));
        matrix.put("downUrl", presence(material == null ? null : material.downUrl()));
        Set<String> identities = nodeIdentities(nodes);
        matrix.put("wxvId/mpvid", identities.isEmpty() ? "absent"
                : identities.stream().map(InlineVideoIdentityMappingInspector::summary)
                        .reduce((left, right) -> left + "," + right).orElse("absent"));
        Set<String> observed = material == null ? Set.of() : publicFieldMatches(material, identities);
        matrix.put("publicAssociationObserved", observed.isEmpty() ? "no" : "yes");
        return new Result(decision, Map.copyOf(matrix), Set.copyOf(identities), reason);
    }

    static Set<String> nodeIdentities(List<InlineVideoNodeInspector.Node> nodes) {
        Set<String> result = new LinkedHashSet<>();
        if (nodes == null) {
            return Set.of();
        }
        for (InlineVideoNodeInspector.Node node : nodes) {
            if (node == null) {
                continue;
            }
            for (Map.Entry<String, String> attribute : node.attributes().entrySet()) {
                String name = attribute.getKey();
                if (!("data-mpvid".equals(name) || "data-vid".equals(name) || "vid".equals(name)
                        || "data-src".equals(name) || "src".equals(name))) {
                    continue;
                }
                String value = attribute.getValue();
                if (value == null) {
                    continue;
                }
                Matcher wxv = WXV.matcher(value);
                while (wxv.find()) {
                    result.add(wxv.group());
                }
                Matcher mpvid = MPVID.matcher(value);
                while (mpvid.find()) {
                    result.add(mpvid.group(1));
                }
                if (("data-mpvid".equals(name) || "data-vid".equals(name) || "vid".equals(name))
                        && value.matches("[A-Za-z0-9_-]{5,}")) {
                    result.add(value);
                }
            }
        }
        return Set.copyOf(result);
    }

    static Set<String> publicFieldMatches(PublicMaterialFields material, Set<String> nodeIdentities) {
        if (material == null || nodeIdentities == null || nodeIdentities.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        List<String> publicValues = List.of(value(material.title()), value(material.description()),
                value(material.downUrl()));
        for (String identity : nodeIdentities) {
            if (hasText(identity) && publicValues.stream().anyMatch(field -> field.contains(identity))) {
                result.add(identity);
            }
        }
        return Set.copyOf(result);
    }

    static String summary(String value) {
        if (!hasText(value)) {
            return "absent";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return "present(len=" + value.trim().length() + ",sha256=" + hex + ")";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String presence(String value) {
        return hasText(value) ? "present" : "absent";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    enum Decision { GO, CONDITIONAL_GO, NO_GO, ENVIRONMENT_BLOCKED }

    record PublicMaterialFields(String materialMediaId, String title, String description, String downUrl) {
    }

    record MappingEvidence(boolean documentedPublicField, boolean repeatedlyVerified,
                           boolean privateLoginDependency, Set<String> explicitAssociatedIds,
                           String environmentBlocker) {
        MappingEvidence {
            explicitAssociatedIds = explicitAssociatedIds == null ? Set.of() : Set.copyOf(explicitAssociatedIds);
        }
    }

    record Result(Decision decision, Map<String, String> fieldMatrix,
                  Set<String> nodeIdentities, String reason) {
    }
}
