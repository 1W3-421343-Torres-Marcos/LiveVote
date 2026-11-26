package com.livevote.results.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Aggregated vote counter per poll option.
 */
@Entity
@Table(name = "option_counts")
public class OptionCountEntity {

    @Id
    @Column(name = "option_id")
    private UUID optionId;

    @Column(name = "poll_id", nullable = false)
    private UUID pollId;

    @Column(nullable = false, length = 200)
    private String text;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "vote_count", nullable = false)
    private long voteCount;

    protected OptionCountEntity() {
    }

    public OptionCountEntity(UUID optionId, UUID pollId, String text, int displayOrder, long voteCount) {
        this.optionId = optionId;
        this.pollId = pollId;
        this.text = text;
        this.displayOrder = displayOrder;
        this.voteCount = voteCount;
    }

    public UUID getOptionId() {
        return optionId;
    }

    public UUID getPollId() {
        return pollId;
    }

    public String getText() {
        return text;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public long getVoteCount() {
        return voteCount;
    }
}
