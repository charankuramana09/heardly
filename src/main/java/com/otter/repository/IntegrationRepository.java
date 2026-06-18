package com.otter.repository;

import com.otter.domain.Integration;
import com.otter.domain.IntegrationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IntegrationRepository extends JpaRepository<Integration, UUID> {

    List<Integration> findAllByUserId(UUID userId);

    Optional<Integration> findByUserIdAndType(UUID userId, IntegrationType type);
}
