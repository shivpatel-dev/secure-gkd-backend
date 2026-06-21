package com.shiv.securegkd.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shiv.securegkd.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GameControllerTests {

    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private GameService gameService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        gameService = mock(GameService.class);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new GameController(gameService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(validator)
                .build();
    }

    @Test
    void validPostGamesReturnsCreated() throws Exception {
        Game game = game(1L, "GTA5", "Grand Theft Auto V");
        when(gameService.createGame("GTA5", "Grand Theft Auto V")).thenReturn(game);

        mockMvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGameRequest(
                                "GTA5",
                                "Grand Theft Auto V"
                        ))))
                .andExpect(status().isCreated());
    }

    @Test
    void validPostGamesReturnsGameResponseData() throws Exception {
        Game game = game(1L, "GTA5", "Grand Theft Auto V");
        when(gameService.createGame("GTA5", "Grand Theft Auto V")).thenReturn(game);

        mockMvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGameRequest(
                                "GTA5",
                                "Grand Theft Auto V"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.code").value("GTA5"))
                .andExpect(jsonPath("$.title").value("Grand Theft Auto V"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-02T03:04:05Z"));
    }

    @Test
    void invalidPostGamesReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGameRequest(
                                "",
                                ""
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidPostGamesReturnsApiErrorFieldErrors() throws Exception {
        mockMvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGameRequest(
                                "",
                                ""
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.path").value("/api/games"))
                .andExpect(jsonPath("$.fieldErrors.code").value("Game code must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.title").value("Game title must not be blank"));
    }

    @Test
    void getGameByCodeReturnsOkWhenFound() throws Exception {
        when(gameService.findByCode("GTA5")).thenReturn(Optional.of(game(1L, "GTA5", "Grand Theft Auto V")));

        mockMvc.perform(get("/api/games/GTA5"))
                .andExpect(status().isOk());
    }

    @Test
    void getGameByCodeReturnsGameResponseDataWhenFound() throws Exception {
        when(gameService.findByCode("GTA5")).thenReturn(Optional.of(game(1L, "GTA5", "Grand Theft Auto V")));

        mockMvc.perform(get("/api/games/GTA5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.code").value("GTA5"))
                .andExpect(jsonPath("$.title").value("Grand Theft Auto V"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-02T03:04:05Z"));
    }

    @Test
    void getGameByCodeReturnsNotFoundWhenMissing() throws Exception {
        when(gameService.findByCode("MISSING")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/games/MISSING"))
                .andExpect(status().isNotFound());
    }

    private Game game(Long id, String code, String title) {
        Game game = new Game(code, title);
        ReflectionTestUtils.setField(game, "id", id);
        ReflectionTestUtils.setField(game, "createdAt", CREATED_AT);
        return game;
    }
}
