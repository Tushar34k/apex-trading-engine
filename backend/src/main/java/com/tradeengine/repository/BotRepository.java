package com.tradeengine.repository;

import com.tradeengine.model.TradingBot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface BotRepository extends JpaRepository<TradingBot, UUID> {
    List<TradingBot> findByUserId(UUID userId);
    List<TradingBot> findByStatus(String status);
}
