package com.shiv.securegkd.game;

import com.shiv.securegkd.ApiError;
import com.shiv.securegkd.openapi.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping
    @Operation(
            summary = "Create a Game",
            description = "Requires a Bearer token for an ADMIN identity.",
            security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH_SCHEME)
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Game created",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GameResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Game request validation failed",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Bearer authentication is missing or unacceptable",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Authenticated identity is not an ADMIN",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "413",
                    description = "JSON request body exceeds 16,384 bytes",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Unexpected application failure",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)
                    )
            )
    })
    public ResponseEntity<GameResponse> createGame(@Valid @RequestBody CreateGameRequest request) {
        Game createdGame = gameService.createGame(request.code(), request.title());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(GameResponse.from(createdGame));
    }

    @GetMapping("/{code}")
    @Operation(
            summary = "Get a Game by code",
            description = "Requires a Bearer token for a USER or ADMIN identity.",
            security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH_SCHEME)
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Game found",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GameResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Bearer authentication is missing or unacceptable",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Authenticated identity lacks USER or ADMIN authority",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Game code was not found",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Unexpected application failure",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)
                    )
            )
    })
    public ResponseEntity<GameResponse> getGameByCode(
            @Parameter(description = "Exact Game code", example = "DEMO-GAME")
            @PathVariable String code
    ) {
        return gameService.findByCode(code)
                .map(GameResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(GameNotFoundException::new);
    }
}
