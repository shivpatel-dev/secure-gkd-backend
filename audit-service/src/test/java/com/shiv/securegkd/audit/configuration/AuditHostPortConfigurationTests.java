package com.shiv.securegkd.audit.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditHostPortConfigurationTests {

    @Test
    void defaultsDirectHostDatasourceToThePortableAuditPort() throws IOException {
        assertThat(resolveDatasourceUrl(Map.of()))
                .isEqualTo("jdbc:postgresql://localhost:55432/secure_gkd_audit");
    }

    @Test
    void appliesAnExplicitAuditDatabaseHostPortToTheDirectHostDatasource() throws IOException {
        assertThat(resolveDatasourceUrl(Map.of("AUDIT_DATABASE_HOST_PORT", "56001")))
                .isEqualTo("jdbc:postgresql://localhost:56001/secure_gkd_audit");
    }

    private String resolveDatasourceUrl(Map<String, Object> overrides) throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addFirst(new MapPropertySource("testOverrides", overrides));
        new YamlPropertySourceLoader()
                .load("auditApplication", new ClassPathResource("application.yaml"))
                .forEach(propertySources::addLast);

        return new PropertySourcesPropertyResolver(propertySources)
                .getRequiredProperty("spring.datasource.url");
    }
}
