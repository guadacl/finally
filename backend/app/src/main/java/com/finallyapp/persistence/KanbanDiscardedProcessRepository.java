package com.finallyapp.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KanbanDiscardedProcessRepository extends JpaRepository<KanbanDiscardedProcessEntity, Long> {
}
