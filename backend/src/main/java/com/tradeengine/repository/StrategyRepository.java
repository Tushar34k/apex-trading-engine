package com.tradeengine.repository;

import com.tradeengine.model.Strategy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface StrategyRepository extends JpaRepository<Strategy, UUID> {
}
