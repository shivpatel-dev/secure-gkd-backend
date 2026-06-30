package com.shiv.securegkd.allocation;

import com.shiv.securegkd.gamekey.GameKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AllocationRepository extends JpaRepository<Allocation, Long> {

    Optional<Allocation> findByGameKey(GameKey gameKey);

    boolean existsByGameKey(GameKey gameKey);
}
