package com.tradeengine.repository;

import com.tradeengine.model.TradePosition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PositionRepository extends JpaRepository<TradePosition, UUID> {
    List<TradePosition> findByUserIdAndStatus(UUID userId, String status);
    List<TradePosition> findByUserId(UUID userId);
    Optional<TradePosition> findByBotIdAndSymbolAndStatus(UUID botId, String symbol, String status);
    boolean existsByBotIdAndSymbolAndStatus(UUID botId, String symbol, String status);
}
