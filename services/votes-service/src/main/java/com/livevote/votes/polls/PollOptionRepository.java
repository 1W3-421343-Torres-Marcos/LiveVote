package com.livevote.votes.polls;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for poll options.
 */
public interface PollOptionRepository extends JpaRepository<PollOptionEntity, UUID> {

    List<PollOptionEntity> findAllByPollIdOrderByDisplayOrderAsc(UUID pollId);

    List<PollOptionEntity> findAllByPollIdInOrderByDisplayOrderAsc(Collection<UUID> pollIds);

    boolean existsByIdAndPollId(UUID id, UUID pollId);
}
