package com.livevote.results.repository;

import com.livevote.results.entity.PollProjectionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for projected polls.
 */
public interface PollProjectionRepository extends JpaRepository<PollProjectionEntity, UUID> {
}
