package com.livevote.results.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Deduplication record guaranteeing at-most-once projection of each voteId.
 */
@Entity
@Table(name = "processed_votes")
public class ProcessedVoteEntity {

    @Id
    @Column(name = "vote_id")
    private UUID voteId;

    @Column(name = "poll_id", nullable = false)
    private UUID pollId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedVoteEntity() {
    }

    public ProcessedVoteEntity(UUID voteId, UUID pollId, Instant processedAt) {
        this.voteId = voteId;
        this.pollId = pollId;
        this.processedAt = processedAt;
    }

    public UUID getVoteId() {
        return voteId;
    }

    public UUID getPollId() {
        return pollId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
