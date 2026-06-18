package com.otter.repository;

import com.otter.domain.Meeting;
import com.otter.domain.MeetingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    List<Meeting> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Meeting> findByIdAndUserId(UUID id, UUID userId);

    List<Meeting> findAllByStatusIn(List<MeetingStatus> statuses);
}
