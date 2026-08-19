package com.shiv.securegkd;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationProfilesTests {

    private static final String DATABASE_URL = "jdbc:postgresql://database.example:5432/secure_gkd";
    private static final String DATABASE_USERNAME = "external_user";
    private static final String DATABASE_PASSWORD = "external_password";

    @Test
    void sharedConfigurationKeepsPersistenceBehaviorAndDefaultsToLocal() throws IOException {
        PropertySourcesPropertyResolver resolver = yamlResolver("application.yaml");

        assertThat(resolver.getRequiredProperty("spring.profiles.default")).isEqualTo("local");
        assertThat(resolver.getRequiredProperty("spring.datasource.driver-class-name"))
                .isEqualTo("org.postgresql.Driver");
        assertThat(resolver.getRequiredProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(resolver.getRequiredProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
        assertThat(resolver.getRequiredProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(resolver.getRequiredProperty("spring.flyway.baseline-on-migrate", Boolean.class)).isFalse();
        assertThat(resolver.getRequiredProperty("spring.flyway.validate-on-migrate", Boolean.class)).isTrue();
        assertThat(resolver.getRequiredProperty("secure-gkd.security.jwt.access-token-lifetime"))
                .isEqualTo("PT15M");
        assertThat(resolver.getRequiredProperty("server.error.include-exception", Boolean.class))
                .isFalse();
        assertThat(resolver.getRequiredProperty("server.error.include-message")).isEqualTo("never");
        assertThat(resolver.getRequiredProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(resolver.getRequiredProperty("server.error.include-binding-errors"))
                .isEqualTo("never");
        assertThatThrownBy(() -> resolver.getRequiredProperty(
                "secure-gkd.security.jwt.signing-key-base64"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT_SIGNING_KEY_BASE64");
    }

    @Test
    void localProfileUsesOnlyAllowedNonSecretDefaults() throws IOException {
        PropertySourcesPropertyResolver resolver = yamlResolver("application-local.yaml");

        assertThat(resolver.getRequiredProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost:5432/secure_gkd");
        assertThat(resolver.getRequiredProperty("spring.datasource.username"))
                .isEqualTo("secure_gkd_user");
        assertThatThrownBy(() -> resolver.getRequiredProperty("spring.datasource.password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD");
    }

    @Test
    void testProfileIsSelectedAndRequiresAnExternalPostgresqlDatasource() throws IOException {
        PropertiesPropertySourceLoader loader = new PropertiesPropertySourceLoader();
        PropertySource<?> activation = loader.load(
                "test-activation",
                new ClassPathResource("application.properties")
        ).get(0);
        PropertySourcesPropertyResolver resolver = yamlResolver("application-test.yaml", externalDatasource());

        assertThat(activation.getProperty("spring.profiles.active")).isEqualTo("test");
        assertThat(resolver.getRequiredProperty("spring.datasource.url")).isEqualTo(DATABASE_URL);
        assertThat(resolver.getRequiredProperty("spring.datasource.username")).isEqualTo(DATABASE_USERNAME);
        assertThat(resolver.getRequiredProperty("spring.datasource.password")).isEqualTo(DATABASE_PASSWORD);
        assertThat(resolver.getRequiredProperty("spring.test.database.replace")).isEqualTo("none");
    }

    @Test
    void productionProfileRequiresEveryDatasourceValueExternally() throws IOException {
        PropertySourcesPropertyResolver missingValues = yamlResolver("application-prod.yaml");

        assertThatThrownBy(() -> missingValues.getRequiredProperty("spring.datasource.url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPRING_DATASOURCE_URL");
        assertThatThrownBy(() -> missingValues.getRequiredProperty("spring.datasource.username"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPRING_DATASOURCE_USERNAME");
        assertThatThrownBy(() -> missingValues.getRequiredProperty("spring.datasource.password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD");

        PropertySourcesPropertyResolver suppliedValues = yamlResolver(
                "application-prod.yaml",
                externalDatasource()
        );

        assertThat(suppliedValues.getRequiredProperty("spring.datasource.url")).isEqualTo(DATABASE_URL);
        assertThat(suppliedValues.getRequiredProperty("spring.datasource.username")).isEqualTo(DATABASE_USERNAME);
        assertThat(suppliedValues.getRequiredProperty("spring.datasource.password")).isEqualTo(DATABASE_PASSWORD);
    }

    private PropertySourcesPropertyResolver yamlResolver(String resourceName) throws IOException {
        return yamlResolver(resourceName, Map.of());
    }

    private PropertySourcesPropertyResolver yamlResolver(
            String resourceName,
            Map<String, Object> externalProperties
    ) throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        if (!externalProperties.isEmpty()) {
            propertySources.addFirst(new MapPropertySource("external-datasource", externalProperties));
        }

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> yamlSources = loader.load(resourceName, new ClassPathResource(resourceName));
        yamlSources.forEach(propertySources::addLast);
        return new PropertySourcesPropertyResolver(propertySources);
    }

    private Map<String, Object> externalDatasource() {
        return Map.of(
                "SPRING_DATASOURCE_URL", DATABASE_URL,
                "SPRING_DATASOURCE_USERNAME", DATABASE_USERNAME,
                "SPRING_DATASOURCE_PASSWORD", DATABASE_PASSWORD
        );
    }
}
