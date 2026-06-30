package com.shiv.securegkd.gamekey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameKeyRepository extends JpaRepository<GameKey, Long> {

    Optional<GameKey> findByCode(String code);

    boolean existsByCode(String code);
}
