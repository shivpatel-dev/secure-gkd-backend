package com.shiv.securegkd.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
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

    private static final String USERNAME = "auth-test-user";
    private static final String PASSWORD = "test-password-value";
    private static final String GAME_CODE = "AUTH-GAME";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthenticationIdentityRepository identityRepository;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    private String passwordHash;

    @BeforeEach
    void setUp() {
        identityRepository.deleteAllInBatch();
        gameRepository.findByCode(GAME_CODE).ifPresent(gameRepository::delete);

        passwordHash = passwordEncoder.encode(PASSWORD);
        identityRepository.saveAndFlush(new AuthenticationIdentity(USERNAME, passwordHash));
        gameRepository.saveAndFlush(new Game(GAME_CODE, "Authentication Test Game"));
    }

    @AfterEach
    void tearDown() {
        identityRepository.deleteAllInBatch();
        gameRepository.findByCode(GAME_CODE).ifPresent(gameRepository::delete);
    }

    @Test
    void persistedEncodedCredentialsReturnMinimalBearerJwtWithStableBoundedClaims() throws Exception {
        Instant beforeRequest = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        MvcResult result = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(USERNAME, PASSWORD)))
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
        assertThat(jwt.getSubject()).isEqualTo(USERNAME);
        assertThat(jwt.getIssuedAt()).isBetween(beforeRequest, afterRequest);
        assertThat(jwt.getExpiresAt()).isEqualTo(
                jwt.getIssuedAt().plus(jwtProperties.accessTokenLifetime())
        );
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("sub", "iat", "exp");
        assertThat(jwt.getClaims().values())
                .doesNotContain(PASSWORD)
                .doesNotContain(passwordHash);
        assertThat(passwordHash).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, passwordHash)).isTrue();
    }

    @Test
    void wrongPasswordAndUnknownUsernameReturnIndistinguishableUnauthorizedResponses() throws Exception {
        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(USERNAME, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownUsername = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("unknown-user", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(wrongPassword.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(unknownUsername.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(unknownUsername.getResponse().getHeaderNames())
                .containsExactlyInAnyOrderElementsOf(wrongPassword.getResponse().getHeaderNames());
    }

    @Test
    void validBearerTokenReachesGameControllerAndMissingTokenIsUnauthorized() throws Exception {
        String accessToken = requestAccessToken();

        mockMvc.perform(get("/api/games/{code}", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(GAME_CODE));

        mockMvc.perform(get("/api/games/{code}", GAME_CODE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedIncorrectlySignedAndExpiredBearerTokensAreUnauthorized() throws Exception {
        mockMvc.perform(get("/api/games/{code}", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer malformed-token"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/games/{code}", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + incorrectlySignedToken()))
                .andExpect(status().isUnauthorized());

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String expiredToken = encode(jwtEncoder, now.minusSeconds(120), now.minusSeconds(60));
        mockMvc.perform(get("/api/games/{code}", GAME_CODE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validJwtOnUnclassifiedRouteIsForbiddenAndHealthRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/unclassified")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + requestAccessToken()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private byte[] credentials(String username, String password) throws Exception {
        return objectMapper.writeValueAsBytes(new TokenRequest(username, password));
    }

    private String requestAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(USERNAME, PASSWORD)))
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

    private String encode(JwtEncoder encoder, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(USERNAME)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
