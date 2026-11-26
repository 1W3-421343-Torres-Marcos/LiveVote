package com.livevote.votes.polls;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Individual anonymous vote constrained uniquely by (poll_id, device_id).
 */
@Entity
@Table(name = "votes")
public class VoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "poll_id", nullable = false)
    private UUID pollId;

    @Column(name = "option_id", nullable = false)
    private UUID optionId;

    @Column(name = "device_id", nullable = false, length = 100)
    private String deviceId;

    @Column(name = "cast_at", nullable = false)
    private Instant castAt;

    protected VoteEntity() {
    }

    public VoteEntity(UUID pollId, UUID optionId, String deviceId, Instant castAt) {
        this.pollId = pollId;
        this.optionId = optionId;
        this.deviceId = deviceId;
        this.castAt = castAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPollId() {
        return pollId;
    }

    public UUID getOptionId() {
        return optionId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Instant getCastAt() {
        return castAt;
    }
}
