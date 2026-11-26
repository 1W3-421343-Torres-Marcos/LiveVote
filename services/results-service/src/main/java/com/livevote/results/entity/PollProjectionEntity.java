package com.livevote.results.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-optimized poll projection stored in postgres-results.
 */
@Entity
@Table(name = "poll_projections")
public class PollProjectionEntity {

    @Id
    @Column(name = "poll_id")
    private UUID pollId;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PollProjectionStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected PollProjectionEntity() {
    }

    public PollProjectionEntity(UUID pollId, String title, PollProjectionStatus status, Instant createdAt) {
        this.pollId = pollId;
        this.title = title;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getPollId() {
        return pollId;
    }

    public String getTitle() {
        return title;
    }

    public PollProjectionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
