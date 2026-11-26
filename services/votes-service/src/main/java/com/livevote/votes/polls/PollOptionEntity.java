package com.livevote.votes.polls;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Selectable option belonging to a poll.
 */
@Entity
@Table(name = "poll_options")
public class PollOptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "poll_id", nullable = false)
    private UUID pollId;

    @Column(nullable = false, length = 200)
    private String text;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected PollOptionEntity() {
    }

    public PollOptionEntity(UUID pollId, String text, int displayOrder) {
        this.pollId = pollId;
        this.text = text;
        this.displayOrder = displayOrder;
    }

    public UUID getId() {
        return id;
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
}
