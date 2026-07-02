package com.shiv.securegkd.gamekey;

import com.shiv.securegkd.game.Game;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GameKeyRepository extends JpaRepository<GameKey, Long> {

    Optional<GameKey> findByCode(String code);

    boolean existsByCode(String code);

    @Query("""
            select gameKey from GameKey gameKey
            where gameKey.game = :game
            and not exists (
                select allocation.id from Allocation allocation
                where allocation.gameKey = gameKey
            )
            order by gameKey.id
            """)
    List<GameKey> findAvailableByGame(@Param("game") Game game, Pageable pageable);
}
