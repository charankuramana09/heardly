package com.otter.repository;

import com.otter.domain.Insight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InsightRepository extends JpaRepository<Insight, UUID> {

    Optional<Insight> findByRecordingId(UUID recordingId);
}
