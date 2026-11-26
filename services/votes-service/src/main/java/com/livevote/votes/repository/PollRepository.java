package com.livevote.votes.repository;

import com.livevote.votes.entity.PollEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for polls.
 */
public interface PollRepository extends JpaRepository<PollEntity, UUID> {

    List<PollEntity> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
}
