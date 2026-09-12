package com.choucj.aiaggregator.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Story 10.1 的依赖与生产管理端点契约，以防配置漂移重新暴露敏感端点。
 */
class ObservabilityConfigurationTest {

    @Test
    void should_include_bom_managed_prometheus_registry_when_pom_is_parsed() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var xpath = XPathFactory.newInstance().newXPath();
        String dependencyPath = "/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency']"
                + "[*[local-name()='groupId']='io.micrometer'"
                + " and *[local-name()='artifactId']='micrometer-registry-prometheus']";
        NodeList dependencies = (NodeList) xpath.evaluate(dependencyPath, pom, XPathConstants.NODESET);
        String version = xpath.evaluate(dependencyPath + "/*[local-name()='version']", pom);

        assertThat(dependencies.getLength()).isEqualTo(1);
        assertThat(version).isBlank();
        assertThat(xpath.evaluate("/*[local-name()='project']/*[local-name()='properties']"
                + "/*[local-name()='micrometer.version']", pom)).isBlank();
    }

    @Test
    void should_expose_only_approved_endpoints_when_prod_profile_is_loaded() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "application-prod", new ClassPathResource("application-prod.yml"));

        Object exposure = sources.stream()
                .map(source -> source.getProperty("management.endpoints.web.exposure.include"))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
        Object envAccess = sources.stream()
                .map(source -> source.getProperty("management.endpoint.env.access"))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);

        assertThat(exposure).isEqualTo("health,info,prometheus");
        assertThat(envAccess).isEqualTo("none");
    }
}
