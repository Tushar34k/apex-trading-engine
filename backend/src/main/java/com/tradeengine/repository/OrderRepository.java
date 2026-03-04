package com.tradeengine.repository;

import com.tradeengine.model.TradeOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<TradeOrder, UUID> {
    List<TradeOrder> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<TradeOrder> findByBotId(UUID botId);
}
