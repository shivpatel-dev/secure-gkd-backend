package com.shiv.securegkd;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.securegkd.allocation.AllocationController;
import com.shiv.securegkd.allocation.AllocationRequest;
import com.shiv.securegkd.allocation.AllocationResponse;
import com.shiv.securegkd.allocation.AllocationService;
import com.shiv.securegkd.authentication.AuthenticationController;
import com.shiv.securegkd.authentication.AuthenticationIdentityRepository;
import com.shiv.securegkd.authentication.TokenRequest;
import com.shiv.securegkd.authentication.TokenService;
import com.shiv.securegkd.game.CreateGameRequest;
import com.shiv.securegkd.game.GameController;
import com.shiv.securegkd.game.GameService;
import com.shiv.securegkd.health.HealthController;
import com.shiv.securegkd.security.SecurityConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        HealthController.class,
        GameController.class,
        AllocationController.class,
        AuthenticationController.class
})
@Import({SecurityConfiguration.class, RequestCorrelationFilter.class})
class OperationalLoggingTests {

    private static final String CLIENT_REQUEST_ID_MARKER = "TEST_ONLY_CLIENT_REQUEST_ID";
    private static final String EXCEPTION_MESSAGE_MARKER = "TEST_ONLY_EXCEPTION_MESSAGE";
    private static final String EXCEPTION_CAUSE_MARKER = "TEST_ONLY_EXCEPTION_CAUSE";
    private static final String BEARER_TOKEN_MARKER = "TEST_ONLY_BEARER_TOKEN";
    private static final String USERNAME_MARKER = "TEST_ONLY_SUBMITTED_USERNAME";
    private static final String PASSWORD_MARKER = "TEST_ONLY_SUBMITTED_PASSWORD";
    private static final String REQUEST_BODY_MARKER = "TEST_ONLY_REQUEST_BODY";
    private static final String GAME_KEY_MARKER = "TEST_ONLY_SECRET_GAME_KEY";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private AllocationService allocationService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private AuthenticationIdentityRepository authenticationIdentityRepository;

    private Logger exceptionLogger;
    private Logger securityLogger;
    private ListAppender<ILoggingEvent> exceptionAppender;
    private ListAppender<ILoggingEvent> securityAppender;

    @BeforeEach
    void captureApplicationLogs() {
        MDC.clear();
        exceptionLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        securityLogger = (Logger) LoggerFactory.getLogger(SecurityConfiguration.class);
        exceptionAppender = attachAppender(exceptionLogger);
        securityAppender = attachAppender(securityLogger);
    }

    @AfterEach
    void stopCapturingApplicationLogs() {
        exceptionLogger.detachAppender(exceptionAppender);
        securityLogger.detachAppender(securityAppender);
        exceptionAppender.stop();
        securityAppender.stop();
        MDC.clear();
    }

    @Test
    void requestsReceiveDistinctServerGeneratedIdsAndClearCorrelationState() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/health")
                        .header(RequestCorrelationFilter.HEADER_NAME, CLIENT_REQUEST_ID_MARKER))
                .andExpect(status().isOk())
                .andReturn();

        String firstRequestId = requestId(first);
        assertThat(firstRequestId)
                .isNotBlank()
                .isNotEqualTo(CLIENT_REQUEST_ID_MARKER);
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();

        MvcResult second = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn();

        String secondRequestId = requestId(second);
        assertThat(secondRequestId).isNotBlank().isNotEqualTo(firstRequestId);
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
        assertThat(applicationEvents()).isEmpty();
    }

    @Test
    void unexpectedFailureIsSafeAndCorrelatableWithoutExceptionDetails() throws Exception {
        AllocationRequest request = new AllocationRequest("request-1");
        when(allocationService.allocate("GTA5", request)).thenThrow(new IllegalStateException(
                EXCEPTION_MESSAGE_MARKER,
                new IllegalArgumentException(EXCEPTION_CAUSE_MARKER)
        ));

        MvcResult result = mockMvc.perform(post("/api/games/GTA5/allocations")
                        .with(user("test-user").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value("/api/games/GTA5/allocations"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn();

        String requestId = requestId(result);
        ILoggingEvent event = singleEvent(exceptionAppender, "unexpected_application_failure");
        assertCorrelatedEvent(event, requestId, "POST", "/api/games/GTA5/allocations", 500);
        assertThat(event.getFormattedMessage())
                .contains("exceptionType=IllegalStateException")
                .doesNotContain(EXCEPTION_MESSAGE_MARKER)
                .doesNotContain(EXCEPTION_CAUSE_MARKER);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(EXCEPTION_MESSAGE_MARKER)
                .doesNotContain(EXCEPTION_CAUSE_MARKER);
    }

    @Test
    void suppliedInvalidBearerIsRejectedWithoutLoggingTheCredential() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/games/GTA5")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN_MARKER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andReturn();

        ILoggingEvent event = singleEvent(securityAppender, "bearer_authentication_rejected");
        assertCorrelatedEvent(event, requestId(result), "GET", "/api/games/GTA5", 401);
        assertThat(formattedApplicationLogs())
                .doesNotContain(BEARER_TOKEN_MARKER)
                .doesNotContain(HttpHeaders.AUTHORIZATION);
        verifyNoInteractions(gameService);
    }

    @Test
    void incorrectCredentialsAreRejectedWithoutLoggingSubmittedValues() throws Exception {
        when(tokenService.issue(any(TokenRequest.class)))
                .thenThrow(new BadCredentialsException(EXCEPTION_MESSAGE_MARKER));
        TokenRequest request = new TokenRequest(USERNAME_MARKER, PASSWORD_MARKER);

        MvcResult result = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andReturn();

        ILoggingEvent event = singleEvent(exceptionAppender, "credential_authentication_rejected");
        assertCorrelatedEvent(event, requestId(result), "POST", "/api/auth/token", 401);
        assertThat(formattedApplicationLogs())
                .doesNotContain(USERNAME_MARKER)
                .doesNotContain(PASSWORD_MARKER)
                .doesNotContain(EXCEPTION_MESSAGE_MARKER);
    }

    @Test
    void authorizationDenialLogsOnlyBoundedCorrelatableContext() throws Exception {
        CreateGameRequest request = new CreateGameRequest("DENIED", REQUEST_BODY_MARKER);

        MvcResult result = mockMvc.perform(post("/api/games")
                        .with(user("TEST_ONLY_PRINCIPAL").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andReturn();

        ILoggingEvent event = singleEvent(securityAppender, "authorization_denied");
        assertCorrelatedEvent(event, requestId(result), "POST", "/api/games", 403);
        assertThat(formattedApplicationLogs())
                .doesNotContain("TEST_ONLY_PRINCIPAL")
                .doesNotContain(REQUEST_BODY_MARKER)
                .doesNotContain("ROLE_USER");
        verifyNoInteractions(gameService);
    }

    @Test
    void missingCredentialsRemainLowNoise() throws Exception {
        mockMvc.perform(get("/api/games/GTA5"))
                .andExpect(status().isUnauthorized());

        assertThat(applicationEvents()).isEmpty();
        verifyNoInteractions(gameService);
    }

    @Test
    void successfulAllocationDoesNotLogItsSecretGameKey() throws Exception {
        AllocationRequest request = new AllocationRequest("request-1");
        AllocationResponse response = new AllocationResponse(
                "GTA5",
                GAME_KEY_MARKER,
                Instant.parse("2026-01-02T03:04:05Z")
        );
        when(allocationService.allocate("GTA5", request)).thenReturn(response);

        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .with(user("test-user").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyCode").value(GAME_KEY_MARKER));

        assertThat(formattedApplicationLogs()).doesNotContain(GAME_KEY_MARKER);
        assertThat(applicationEvents()).isEmpty();
    }

    private ListAppender<ILoggingEvent> attachAppender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private String requestId(MvcResult result) {
        return result.getResponse().getHeader(RequestCorrelationFilter.HEADER_NAME);
    }

    private ILoggingEvent singleEvent(
            ListAppender<ILoggingEvent> appender,
            String eventCategory
    ) {
        List<ILoggingEvent> matchingEvents = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("event=" + eventCategory))
                .toList();
        assertThat(matchingEvents).hasSize(1);
        return matchingEvents.get(0);
    }

    private void assertCorrelatedEvent(
            ILoggingEvent event,
            String requestId,
            String method,
            String path,
            int status
    ) {
        assertThat(requestId).isNotBlank();
        assertThat(event.getMDCPropertyMap()).containsEntry(RequestCorrelationFilter.MDC_KEY, requestId);
        assertThat(event.getFormattedMessage())
                .contains("requestId=" + requestId)
                .contains("method=" + method)
                .contains("path=" + path)
                .contains("status=" + status);
    }

    private List<ILoggingEvent> applicationEvents() {
        return Stream.concat(exceptionAppender.list.stream(), securityAppender.list.stream())
                .toList();
    }

    private String formattedApplicationLogs() {
        return applicationEvents().stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
