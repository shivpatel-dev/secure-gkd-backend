package com.shiv.securegkd.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.game.CreateGameRequest;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.allocation.AllocationRepository;
import com.shiv.securegkd.allocation.AllocationRequest;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuthenticationIntegrationTests {

    private static final String USER_USERNAME = "auth-test-user";
    private static final String ADMIN_USERNAME = "auth-test-admin";
    private static final String PASSWORD = "test-password-value";
    private static final String GAME_CODE = "AUTH-GAME";
    private static final String CREATED_GAME_CODE = "AUTH-CREATED-GAME";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthenticationIdentityRepository identityRepository;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private GameKeyRepository gameKeyRepository;

    @Autowired
    private AllocationRepository allocationRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Autowired
    private JwtProperties jwtProperties;

    private String passwordHash;

    @BeforeEach
    void setUp() {
        deleteTestData();

        passwordHash = passwordEncoder.encode(PASSWORD);
        identityRepository.saveAndFlush(new AuthenticationIdentity(
                USER_USERNAME,
                passwordHash,
                AuthenticationRole.USER
        ));
        identityRepository.saveAndFlush(new AuthenticationIdentity(
                ADMIN_USERNAME,
                passwordHash,
                AuthenticationRole.ADMIN
        ));
        Game game = gameRepository.saveAndFlush(new Game(GAME_CODE, "Authentication Test Game"));
        gameKeyRepository.saveAndFlush(new GameKey(game, "AUTH-KEY-USER"));
        gameKeyRepository.saveAndFlush(new GameKey(game, "AUTH-KEY-ADMIN"));
    }

    @AfterEach
    void tearDown() {
        deleteTestData();
    }

    @Test
    void userCredentialsReturnBearerJwtWithRoleAndStableBoundedClaims() throws Exception {
        Instant beforeRequest = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        MvcResult result = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(USER_USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds")
                        .value(jwtProperties.accessTokenLifetime().toSeconds()))
                .andReturn();

        Instant afterRequest = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        Set<String> responseFields = new HashSet<>();
        response.fieldNames().forEachRemaining(responseFields::add);
        assertThat(responseFields).containsExactlyInAnyOrder(
                "accessToken",
                "tokenType",
                "expiresInSeconds"
        );
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(PASSWORD)
                .doesNotContain(passwordHash);

        Jwt jwt = jwtDecoder.decode(response.required("accessToken").textValue());
        assertThat(jwt.getSubject()).isEqualTo(USER_USERNAME);
        assertThat(jwt.getIssuedAt()).isBetween(beforeRequest, afterRequest);
        assertThat(jwt.getExpiresAt()).isEqualTo(
                jwt.getIssuedAt().plus(jwtProperties.accessTokenLifetime())
        );
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("sub", "iat", "exp", "roles");
        assertThat(jwt.getClaims().values())
                .doesNotContain(PASSWORD)
                .doesNotContain(passwordHash);
        assertThat(passwordHash).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, passwordHash)).isTrue();
    }

    @Test
    void persistedRolesBecomeCredentialAuthoritiesAndAdminJwtClaim() throws Exception {
        assertThat(identityRepository.findByUsername(USER_USERNAME).orElseThrow().getRole())
                .isEqualTo(AuthenticationRole.USER);
        assertThat(identityRepository.findByUsername(ADMIN_USERNAME).orElseThrow().getRole())
                .isEqualTo(AuthenticationRole.ADMIN);

        UserDetails userDetails = userDetailsService.loadUserByUsername(USER_USERNAME);
        UserDetails adminDetails = userDetailsService.loadUserByUsername(ADMIN_USERNAME);
        assertThat(userDetails.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        assertThat(adminDetails.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");

        Jwt adminJwt = jwtDecoder.decode(requestAccessToken(ADMIN_USERNAME));
        assertThat(adminJwt.getSubject()).isEqualTo(ADMIN_USERNAME);
        assertThat(adminJwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
    }

    @Test
    void wrongPasswordAndUnknownUsernameReturnIndistinguishableUnauthorizedResponses() throws Exception {
        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(USER_USERNAME, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownUsername = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("unknown-user", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode wrongPasswordBody = objectMapper.readTree(
                wrongPassword.getResponse().getContentAsByteArray()
        );
        JsonNode unknownUsernameBody = objectMapper.readTree(
                unknownUsername.getResponse().getContentAsByteArray()
        );
        assertThat(wrongPasswordBody.required("timestamp").textValue()).isNotBlank();
        assertThat(wrongPasswordBody.required("status").intValue()).isEqualTo(401);
        assertThat(wrongPasswordBody.required("error").textValue()).isEqualTo("Unauthorized");
        assertThat(wrongPasswordBody.required("message").textValue())
                .isEqualTo("Authentication required");
        assertThat(wrongPasswordBody.required("path").textValue()).isEqualTo("/api/auth/token");
        assertThat(wrongPasswordBody.required("fieldErrors").isEmpty()).isTrue();
        assertThat(wrongPassword.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(unknownUsername.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_JSON_VALUE);

        ObjectNode normalizedWrongPassword = wrongPasswordBody.deepCopy();
        ObjectNode normalizedUnknownUsername = unknownUsernameBody.deepCopy();
        normalizedWrongPassword.remove("timestamp");
        normalizedUnknownUsername.remove("timestamp");
        assertThat(normalizedUnknownUsername).isEqualTo(normalizedWrongPassword);
        assertThat(wrongPassword.getResponse().getContentAsString())
                .doesNotContain("wrong-password")
                .doesNotContain(PASSWORD)
                .doesNotContain(passwordHash);
        assertThat(unknownUsername.getResponse().getContentAsString())
                .doesNotContain("unknown-user")
                .doesNotContain(PASSWORD)
                .doesNotContain(passwordHash);
    }

    @Test
    void userTokenCanRetrieveAndAllocateButCannotCreateGame() throws Exception {
        String accessToken = requestAccessToken(USER_USERNAME);

        mockMvc.perform(get("/api/games/{code}", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(GAME_CODE));

        mockMvc.perform(post("/api/games/{gameCode}/allocations", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new AllocationRequest("auth-user-allocation"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameCode").value(GAME_CODE));

        mockMvc.perform(post("/api/games")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new CreateGameRequest(CREATED_GAME_CODE, "Created by user")
                        )))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminTokenCanCreateRetrieveAndAllocate() throws Exception {
        String accessToken = requestAccessToken(ADMIN_USERNAME);

        mockMvc.perform(post("/api/games")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new CreateGameRequest(CREATED_GAME_CODE, "Created by admin")
                        )))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(CREATED_GAME_CODE));

        mockMvc.perform(get("/api/games/{code}", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(GAME_CODE));

        mockMvc.perform(post("/api/games/{gameCode}/allocations", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new AllocationRequest("auth-admin-allocation"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameCode").value(GAME_CODE));
    }

    @Test
    void anonymousProtectedOperationsAreUnauthorized() throws Exception {
        mockMvc.perform(get("/api/games/{code}", GAME_CODE))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new CreateGameRequest(CREATED_GAME_CODE, "Anonymous game")
                        )))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/games/{gameCode}/allocations", GAME_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new AllocationRequest("anonymous-allocation"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedIncorrectlySignedAndExpiredBearerTokensAreUnauthorized() throws Exception {
        MvcResult malformed = mockMvc.perform(get("/api/games/{code}", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer malformed-token"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertCommonError(malformed, 401, "Unauthorized", "Authentication required",
                "/api/games/" + GAME_CODE);

        MvcResult incorrectlySigned = mockMvc.perform(get("/api/games/{code}", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + incorrectlySignedToken()))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertCommonError(incorrectlySigned, 401, "Unauthorized", "Authentication required",
                "/api/games/" + GAME_CODE);

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String expiredToken = encode(jwtEncoder, now.minusSeconds(120), now.minusSeconds(60));
        MvcResult expired = mockMvc.perform(get("/api/games/{code}", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertCommonError(expired, 401, "Unauthorized", "Authentication required",
                "/api/games/" + GAME_CODE);
    }

    @Test
    void validJwtWithoutRolesAuthenticatesButIsForbiddenOnRoleProtectedRoute() throws Exception {
        assertAuthenticatesWithoutApplicationAuthoritiesAndIsForbidden(
                validlySignedToken(null)
        );
    }

    @Test
    void validJwtWithUnsupportedRoleAuthenticatesWithoutCreatingArbitraryAuthority() throws Exception {
        assertAuthenticatesWithoutApplicationAuthoritiesAndIsForbidden(
                validlySignedToken(List.of("SUPERUSER"))
        );
    }

    @Test
    void authorizedRequestsPreserveValidationAndNotFoundBehavior() throws Exception {
        String adminAccessToken = requestAccessToken(ADMIN_USERNAME);
        String userAccessToken = requestAccessToken(USER_USERNAME);

        mockMvc.perform(post("/api/games")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new CreateGameRequest("", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.code").value("Game code must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.title").value("Game title must not be blank"));

        mockMvc.perform(post("/api/games/{gameCode}/allocations", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new AllocationRequest(""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.idempotencyKey")
                        .value("idempotencyKey is required"));

        mockMvc.perform(get("/api/games/{code}", "AUTH-MISSING-GAME")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Game not found"))
                .andExpect(jsonPath("$.path").value("/api/games/AUTH-MISSING-GAME"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void validJwtOnUnclassifiedRouteIsForbiddenAndHealthRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/unclassified")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requestAccessToken(USER_USERNAME)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(jsonPath("$.path").value("/api/unclassified"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private byte[] credentials(String username, String password) throws Exception {
        return objectMapper.writeValueAsBytes(new TokenRequest(username, password));
    }

    private String requestAccessToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .required("accessToken")
                .textValue();
    }

    private String incorrectlySignedToken() {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        SecretKey secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        JwtEncoder otherEncoder = new NimbusJwtEncoder(
                new ImmutableSecret<SecurityContext>(secretKey)
        );
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return encode(otherEncoder, now, now.plusSeconds(60));
    }

    private String validlySignedToken(List<String> roles) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return encode(jwtEncoder, now, now.plusSeconds(60), roles);
    }

    private String encode(JwtEncoder encoder, Instant issuedAt, Instant expiresAt) {
        return encode(encoder, issuedAt, expiresAt, null);
    }

    private String encode(
            JwtEncoder encoder,
            Instant issuedAt,
            Instant expiresAt,
            List<String> roles
    ) {
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .subject(USER_USERNAME)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt);
        if (roles != null) {
            claimsBuilder.claim("roles", roles);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claimsBuilder.build())).getTokenValue();
    }

    private void assertAuthenticatesWithoutApplicationAuthoritiesAndIsForbidden(
            String accessToken
    ) throws Exception {
        mockMvc.perform(get("/api/games/{code}", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(jsonPath("$.path").value("/api/games/" + GAME_CODE))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        AbstractAuthenticationToken authentication = jwtAuthenticationConverter.convert(
                jwtDecoder.decode(accessToken)
        );
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getAuthorities()).isEmpty();
    }

    private void assertCommonError(
            MvcResult result,
            int status,
            String error,
            String message,
            String path
    ) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(body.required("timestamp").textValue()).isNotBlank();
        assertThat(body.required("status").intValue()).isEqualTo(status);
        assertThat(body.required("error").textValue()).isEqualTo(error);
        assertThat(body.required("message").textValue()).isEqualTo(message);
        assertThat(body.required("path").textValue()).isEqualTo(path);
        assertThat(body.required("fieldErrors").isEmpty()).isTrue();
    }

    private void deleteTestData() {
        identityRepository.deleteAllInBatch();
        idempotencyRecordRepository.deleteAllInBatch();
        allocationRepository.deleteAllInBatch();
        gameKeyRepository.deleteAllInBatch();
        gameRepository.findByCode(GAME_CODE).ifPresent(gameRepository::delete);
        gameRepository.findByCode(CREATED_GAME_CODE).ifPresent(gameRepository::delete);
    }
}
