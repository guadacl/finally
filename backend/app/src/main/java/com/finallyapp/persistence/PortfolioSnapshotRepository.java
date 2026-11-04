package com.finallyapp.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshotEntity, UUID> {

    /** Newest first; use a {@code PageRequest.of(0, n)} for the most recent {@code n}. */
    List<PortfolioSnapshotEntity> findByUserIdOrderByRecordedAtDesc(String userId, Pageable page);
}
