package com.shiv.securegkd.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.securegkd.allocation.AllocationController;
import com.shiv.securegkd.allocation.AllocationService;
import com.shiv.securegkd.authentication.AuthenticationController;
import com.shiv.securegkd.authentication.AuthenticationIdentityRepository;
import com.shiv.securegkd.authentication.TokenService;
import com.shiv.securegkd.game.GameController;
import com.shiv.securegkd.game.GameService;
import com.shiv.securegkd.health.HealthController;
import com.shiv.securegkd.security.SecurityConfiguration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        HealthController.class,
        AuthenticationController.class,
        GameController.class,
        AllocationController.class
})
@Import({SecurityConfiguration.class, OpenApiConfiguration.class})
@ImportAutoConfiguration({
        SpringDocConfiguration.class,
        SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class,
        SwaggerConfig.class,
        SwaggerUiConfigProperties.class,
        SwaggerUiOAuthProperties.class
})
class OpenApiDocumentationTests {

    private static final Set<String> APPLICATION_PATHS = Set.of(
            "/api/health",
            "/api/auth/token",
            "/api/games",
            "/api/games/{code}",
            "/api/games/{gameCode}/allocations"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private AllocationService allocationService;

    @MockitoBean
    private AuthenticationIdentityRepository authenticationIdentityRepository;

    @Test
    void generatedOpenApiIsPublicAndDescribesOnlyTheCurrentApplicationApi() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        JsonNode contract = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        Set<String> documentedPaths = new HashSet<>();
        contract.required("paths").fieldNames().forEachRemaining(documentedPaths::add);

        assertThat(documentedPaths).containsExactlyInAnyOrderElementsOf(APPLICATION_PATHS);
        assertThat(contract.at("/info/title").textValue())
                .isEqualTo("Secure Game Key Distribution API");
        assertThat(contract.at("/info/version").textValue()).isEqualTo("0.0.1-SNAPSHOT");
    }

    @Test
    void contractDeclaresBearerSecurityOnlyForProtectedOperations() throws Exception {
        JsonNode contract = generatedContract();

        JsonNode bearerScheme = contract.at("/components/securitySchemes/bearerAuth");
        assertThat(bearerScheme.required("type").textValue()).isEqualTo("http");
        assertThat(bearerScheme.required("scheme").textValue()).isEqualTo("bearer");
        assertThat(bearerScheme.required("bearerFormat").textValue()).isEqualTo("JWT");

        assertThat(contract.at("/paths/~1api~1health/get").has("security")).isFalse();
        assertThat(contract.at("/paths/~1api~1auth~1token/post").has("security")).isFalse();
        assertBearerSecurity(contract.at("/paths/~1api~1games/post"));
        assertBearerSecurity(contract.at("/paths/~1api~1games~1{code}/get"));
        assertBearerSecurity(
                contract.at("/paths/~1api~1games~1{gameCode}~1allocations/post")
        );
    }

    @Test
    void contractUsesRuntimeDtosAndValidationBoundaries() throws Exception {
        JsonNode contract = generatedContract();

        assertThat(contract.at(
                "/paths/~1api~1auth~1token/post/requestBody/content/application~1json/schema/$ref"
        ).textValue()).isEqualTo("#/components/schemas/TokenRequest");
        assertThat(contract.at(
                "/paths/~1api~1games/post/requestBody/content/application~1json/schema/$ref"
        ).textValue()).isEqualTo("#/components/schemas/CreateGameRequest");
        assertThat(contract.at(
                "/paths/~1api~1games~1{gameCode}~1allocations/post/requestBody/content/"
                        + "application~1json/schema/$ref"
        ).textValue()).isEqualTo("#/components/schemas/AllocationRequest");
        assertRequiredProperties(
                contract.at("/components/schemas/TokenRequest"),
                "username", "password"
        );
        assertRequiredProperties(
                contract.at("/components/schemas/CreateGameRequest"),
                "code", "title"
        );
        assertRequiredProperties(
                contract.at("/components/schemas/AllocationRequest"),
                "idempotencyKey"
        );
        assertThat(contract.at("/components/schemas/TokenRequest/properties/username/maxLength")
                .intValue()).isEqualTo(100);
        assertThat(contract.at("/components/schemas/TokenRequest/properties/password/maxLength")
                .intValue()).isEqualTo(1024);
        assertThat(contract.at("/components/schemas/CreateGameRequest/properties/code/maxLength")
                .intValue()).isEqualTo(50);
        assertThat(contract.at("/components/schemas/CreateGameRequest/properties/title/maxLength")
                .intValue()).isEqualTo(255);
        assertThat(contract.at(
                "/components/schemas/AllocationRequest/properties/idempotencyKey/maxLength"
        ).intValue()).isEqualTo(255);
        assertThat(contract.at("/components/schemas/HealthResponse/properties/status").isObject())
                .isTrue();
        assertThat(contract.at("/components/schemas/ApiError/properties/fieldErrors").isObject())
                .isTrue();
    }

    @Test
    void contractDocumentsOnlyApplicableCoveredResponses() throws Exception {
        JsonNode contract = generatedContract();

        assertResponseCodes(
                contract.at("/paths/~1api~1health/get/responses"),
                "200"
        );
        assertResponseCodes(
                contract.at("/paths/~1api~1auth~1token/post/responses"),
                "200", "400", "401", "413", "500"
        );
        assertResponseCodes(
                contract.at("/paths/~1api~1games/post/responses"),
                "201", "400", "401", "403", "413", "500"
        );
        assertResponseCodes(
                contract.at("/paths/~1api~1games~1{code}/get/responses"),
                "200", "401", "403", "404", "500"
        );
        assertResponseCodes(
                contract.at("/paths/~1api~1games~1{gameCode}~1allocations/post/responses"),
                "201", "400", "401", "403", "413", "500"
        );

        assertApiErrorSchema(
                contract.at("/paths/~1api~1games~1{code}/get/responses/404")
        );
        assertApiErrorSchema(
                contract.at("/paths/~1api~1games~1{code}/get/responses/401")
        );
        assertApiErrorSchema(
                contract.at("/paths/~1api~1games/post/responses/403")
        );
        assertApiErrorSchema(
                contract.at(
                        "/paths/~1api~1games~1{gameCode}~1allocations/post/responses/500"
                )
        );

        JsonNode apiErrorProperties = contract.at("/components/schemas/ApiError/properties");
        assertThat(apiErrorProperties.required("status").has("example")).isFalse();
        assertThat(apiErrorProperties.required("error").has("example")).isFalse();
        assertThat(apiErrorProperties.required("message").has("example")).isFalse();
        assertThat(apiErrorProperties.required("path").has("example")).isFalse();
        assertThat(apiErrorProperties.required("fieldErrors").has("example")).isFalse();
    }

    @Test
    void swaggerUiAndItsGeneratedConfigurationArePubliclyReachable() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }

    @Test
    void documentationAccessDoesNotMakeUnclassifiedRoutesPublic() throws Exception {
        mockMvc.perform(get("/api/unclassified").with(user("test-user").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));

        mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode generatedContract() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertBearerSecurity(JsonNode operation) {
        assertThat(operation.at("/security/0/bearerAuth").isArray()).isTrue();
    }

    private void assertResponseCodes(JsonNode responses, String... expectedCodes) {
        Set<String> actualCodes = new HashSet<>();
        responses.fieldNames().forEachRemaining(actualCodes::add);
        assertThat(actualCodes).containsExactlyInAnyOrder(expectedCodes);
    }

    private void assertApiErrorSchema(JsonNode response) {
        assertThat(response.at("/content/application~1json/schema/$ref").textValue())
                .isEqualTo("#/components/schemas/ApiError");
    }

    private void assertRequiredProperties(JsonNode schema, String... expectedProperties) {
        assertThat(schema.required("required"))
                .extracting(JsonNode::textValue)
                .containsExactlyInAnyOrder(expectedProperties);
    }
}
