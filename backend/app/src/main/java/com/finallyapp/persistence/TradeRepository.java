package com.finallyapp.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<TradeEntity, UUID> {

    long countByUserId(String userId);
}
