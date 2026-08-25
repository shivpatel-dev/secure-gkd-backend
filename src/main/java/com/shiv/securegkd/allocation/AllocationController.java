package com.shiv.securegkd.allocation;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games/{gameCode}/allocations")
public class AllocationController {

    private final AllocationService allocationService;

    public AllocationController(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    @PostMapping
    @Operation(
            summary = "Allocate an available GameKey",
            description = "Requires a Bearer token for a USER or ADMIN identity. Reusing the "
                    + "same idempotency key returns the original allocation result.",
            security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH_SCHEME)
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "GameKey allocated, or original allocation returned for a reused key",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AllocationResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Allocation request validation failed",
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
                    description = "Authenticated identity lacks USER or ADMIN authority",
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
    public ResponseEntity<AllocationResponse> allocate(
            @Parameter(description = "Exact Game code", example = "DEMO-GAME")
            @PathVariable String gameCode,
            @Valid @RequestBody AllocationRequest request
    ) {
        AllocationResponse response = allocationService.allocate(gameCode, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}
