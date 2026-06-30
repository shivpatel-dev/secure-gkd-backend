package com.shiv.securegkd.allocation;

import com.shiv.securegkd.gamekey.GameKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "allocations")
public class Allocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_key_id", nullable = false, unique = true)
    private GameKey gameKey;

    @Column(nullable = false, updatable = false)
    private Instant allocatedAt;

    protected Allocation() {
    }

    public Allocation(GameKey gameKey) {
        this.gameKey = gameKey;
    }

    @PrePersist
    void prePersist() {
        if (allocatedAt == null) {
            allocatedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public GameKey getGameKey() {
        return gameKey;
    }

    public Instant getAllocatedAt() {
        return allocatedAt;
    }
}
