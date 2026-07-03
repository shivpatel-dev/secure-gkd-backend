package com.shiv.securegkd.idempotency;

import com.shiv.securegkd.allocation.Allocation;
import com.shiv.securegkd.allocation.AllocationRepository;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdempotencyRecordRepositoryTests {

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private GameKeyRepository gameKeyRepository;

    @Autowired
    private AllocationRepository allocationRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Test
    void savesIdempotencyRecord() {
        deleteAll();
        Allocation allocation = saveAllocation("GTA5", "GTA5-KEY-001");

        IdempotencyRecord savedIdempotencyRecord = idempotencyRecordRepository.saveAndFlush(
                new IdempotencyRecord("idem-key-001", allocation)
        );

        assertThat(savedIdempotencyRecord.getId()).isNotNull();
        assertThat(savedIdempotencyRecord.getCreatedAt()).isNotNull();
        assertThat(savedIdempotencyRecord.getIdempotencyKey()).isEqualTo("idem-key-001");
        assertThat(savedIdempotencyRecord.getAllocation().getId()).isEqualTo(allocation.getId());
    }

    @Test
    void findsIdempotencyRecordByIdempotencyKey() {
        deleteAll();
        Allocation allocation = saveAllocation("GTA5", "GTA5-KEY-001");
        idempotencyRecordRepository.saveAndFlush(new IdempotencyRecord("idem-key-001", allocation));

        assertThat(idempotencyRecordRepository.findByIdempotencyKey("idem-key-001"))
                .isPresent()
                .get()
                .extracting(IdempotencyRecord::getIdempotencyKey)
                .isEqualTo("idem-key-001");
    }

    @Test
    void checksWhetherIdempotencyRecordExistsByIdempotencyKey() {
        deleteAll();
        Allocation allocation = saveAllocation("GTA5", "GTA5-KEY-001");
        idempotencyRecordRepository.saveAndFlush(new IdempotencyRecord("idem-key-001", allocation));

        assertThat(idempotencyRecordRepository.existsByIdempotencyKey("idem-key-001")).isTrue();
        assertThat(idempotencyRecordRepository.existsByIdempotencyKey("idem-key-002")).isFalse();
    }

    @Test
    void rejectsDuplicateIdempotencyKey() {
        deleteAll();
        Allocation firstAllocation = saveAllocation("GTA5", "GTA5-KEY-001");
        Allocation secondAllocation = saveAllocation("RDR2", "RDR2-KEY-001");
        idempotencyRecordRepository.saveAndFlush(new IdempotencyRecord("idem-key-001", firstAllocation));

        assertThatThrownBy(() ->
                idempotencyRecordRepository.saveAndFlush(new IdempotencyRecord("idem-key-001", secondAllocation))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Allocation saveAllocation(String gameCode, String gameKeyCode) {
        Game game = gameRepository.saveAndFlush(new Game(gameCode, gameCode + " title"));
        GameKey gameKey = gameKeyRepository.saveAndFlush(new GameKey(game, gameKeyCode));
        return allocationRepository.saveAndFlush(new Allocation(gameKey));
    }

    private void deleteAll() {
        idempotencyRecordRepository.deleteAll();
        allocationRepository.deleteAll();
        gameKeyRepository.deleteAll();
        gameRepository.deleteAll();
    }
}
