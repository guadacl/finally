package com.finallyapp.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {

    /**
     * Newest first ({@code role} descending as tie-break, so "user" sorts before "assistant" of the same instant once
     * reversed); use a {@code PageRequest.of(0, n)} for the most recent {@code n}.
     */
    List<ChatMessageEntity> findByUserIdOrderByCreatedAtDescRoleDesc(String userId, Pageable page);
}
