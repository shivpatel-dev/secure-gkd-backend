package com.shiv.securegkd.allocation;

import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.idempotency.IdempotencyRecord;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllocationServiceTests {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameKeyRepository gameKeyRepository;

    @Mock
    private AllocationRepository allocationRepository;

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @InjectMocks
    private AllocationService allocationService;

    @Test
    void allocateSavesAllocationAndIdempotencyRecordForNewKey() {
        Game game = new Game("GTA5", "Grand Theft Auto V");
        GameKey gameKey = new GameKey(game, "GTA5-KEY-001");

        when(idempotencyRecordRepository.findByIdempotencyKey("request-1")).thenReturn(Optional.empty());
        when(gameRepository.findByCode("GTA5")).thenReturn(Optional.of(game));
        when(gameKeyRepository.findAvailableByGame(eq(game), any(Pageable.class))).thenReturn(List.of(gameKey));
        when(allocationRepository.save(any(Allocation.class))).thenAnswer(invocation -> {
            Allocation allocation = invocation.getArgument(0);
            allocation.prePersist();
            return allocation;
        });

        AllocationResponse response = allocationService.allocate("GTA5", new AllocationRequest("request-1"));

        assertThat(response.gameCode()).isEqualTo("GTA5");
        assertThat(response.keyCode()).isEqualTo("GTA5-KEY-001");
        assertThat(response.allocatedAt()).isNotNull();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(gameKeyRepository).findAvailableByGame(eq(game), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1);

        ArgumentCaptor<Allocation> allocationCaptor = ArgumentCaptor.forClass(Allocation.class);
        verify(allocationRepository).save(allocationCaptor.capture());
        assertThat(allocationCaptor.getValue().getGameKey()).isSameAs(gameKey);

        ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(idempotencyRecordCaptor.capture());
        assertThat(idempotencyRecordCaptor.getValue().getIdempotencyKey()).isEqualTo("request-1");
        assertThat(idempotencyRecordCaptor.getValue().getAllocation()).isSameAs(allocationCaptor.getValue());
    }

    @Test
    void allocateReturnsOriginalAllocationForExistingIdempotencyKey() {
        Game game = new Game("GTA5", "Grand Theft Auto V");
        GameKey gameKey = new GameKey(game, "GTA5-KEY-001");
        Allocation allocation = new Allocation(gameKey);
        allocation.prePersist();
        IdempotencyRecord idempotencyRecord = new IdempotencyRecord("request-1", allocation);

        when(idempotencyRecordRepository.findByIdempotencyKey("request-1"))
                .thenReturn(Optional.of(idempotencyRecord));

        AllocationResponse response = allocationService.allocate("GTA5", new AllocationRequest("request-1"));

        assertThat(response.gameCode()).isEqualTo("GTA5");
        assertThat(response.keyCode()).isEqualTo("GTA5-KEY-001");
        assertThat(response.allocatedAt()).isEqualTo(allocation.getAllocatedAt());

        verifyNoInteractions(gameRepository, gameKeyRepository, allocationRepository);
        verify(idempotencyRecordRepository, never()).save(any(IdempotencyRecord.class));
    }

    @Test
    void allocateFailsWhenGameDoesNotExist() {
        when(idempotencyRecordRepository.findByIdempotencyKey("request-1")).thenReturn(Optional.empty());
        when(gameRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> allocationService.allocate("UNKNOWN", new AllocationRequest("request-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Game not found: UNKNOWN");

        verifyNoInteractions(gameKeyRepository, allocationRepository);
        verify(idempotencyRecordRepository, never()).save(any(IdempotencyRecord.class));
    }

    @Test
    void allocateFailsWhenNoGameKeyIsAvailable() {
        Game game = new Game("GTA5", "Grand Theft Auto V");

        when(idempotencyRecordRepository.findByIdempotencyKey("request-1")).thenReturn(Optional.empty());
        when(gameRepository.findByCode("GTA5")).thenReturn(Optional.of(game));
        when(gameKeyRepository.findAvailableByGame(eq(game), any(Pageable.class))).thenReturn(List.of());

        assertThatThrownBy(() -> allocationService.allocate("GTA5", new AllocationRequest("request-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No available game keys for game: GTA5");

        verify(allocationRepository, never()).save(any(Allocation.class));
        verify(idempotencyRecordRepository, never()).save(any(IdempotencyRecord.class));
    }
}
