package com.shiv.securegkd.allocation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
    public ResponseEntity<AllocationResponse> allocate(
            @PathVariable String gameCode,
            @Valid @RequestBody AllocationRequest request
    ) {
        AllocationResponse response = allocationService.allocate(gameCode, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}
